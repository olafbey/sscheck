package es.ucm.fdi.sscheck.spark.streaming

import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import org.specs2.ScalaCheck
import org.specs2.scalacheck.{Parameters, ScalaCheckProperty}
import org.specs2.specification.BeforeAfterEach
import org.specs2.execute.AsResult

import org.scalacheck.{Prop, Gen}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Prop.AnyOperators

import org.apache.spark._
import org.apache.spark.streaming.{Duration, StreamingContext}
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.scheduler.{StreamingListener, StreamingListenerReceiverStarted, StreamingListenerBatchCompleted}

import scala.concurrent._
import scala.concurrent.duration._
import ExecutionContext.Implicits.global

import com.typesafe.scalalogging.slf4j.Logging

import akka.actor.ActorSelection
import es.ucm.fdi.sscheck.spark.SharedSparkContextBeforeAfterAll
import es.ucm.fdi.sscheck.spark.streaming.receiver.ProxyReceiverActor

/*
 * TODO
 * - wait to send data for new batch onBatchCompleted
 * - identify each test case: try to use the test id
 * - add assertions and collect their exceptions with an accumulator
 * - stop the context only after all the batches for all the test cases have been
 * processed. An approach could be storing the start time and number of batches of 
 * the test case that starts last, in a synchronized variable for the Specification, 
 * so in after() we register a listener that waits until the time of the last completed 
 * batch corresponds to the last batch of the last test case
 * If the context is stopped before the receiver has started then due to 
 * https://issues.apache.org/jira/browse/SPARK-5681 the streaming context will keep running. But
 * that won't happen here because we don't send data until the receiver has started 
 * - test with more than one property: should not be a problem due to sequential. Probably
 * exceptions will be thrown due to https://issues.apache.org/jira/browse/SPARK-8743, but 
 * that is solved and fixed for Spark 1.4.2
 * - encapsulate in a trait for nice code reuse
 * - add some test examples: use TL generators for that?
 * */

object TestCaseId {
  var currentId = 0
  def next() : TestCaseId = synchronized {
    currentId += 1
    TestCaseId(currentId)
  }
}
case class TestCaseId(id : Int) 

@RunWith(classOf[JUnitRunner])
class StreamingContextActorReceiverTest extends org.specs2.Specification 
                     with org.specs2.matcher.MustThrownExpectations
                     with BeforeAfterEach
                     with SharedSparkContextBeforeAfterAll
                     with ScalaCheck 
                     with Logging {
   
  override def sparkMaster : String = "local[5]"
  
  var maybeSsc : Option[StreamingContext] = None
  // with too small batch intervals the local machine just cannot handle the work
  def batchDuration = Duration(300) // Duration(500) // Duration(10)
  // def batchDuration = Duration(10)
  
  override def before : Unit = {
    assert(maybeSsc.isEmpty)
    maybeSsc = Some(new StreamingContext(sc, batchDuration))
  }
  override def after : Unit = {
    assert(! maybeSsc.isEmpty)
    // TODO: block until all the batches have been processed: could 
    // send a signal in the prop after the last test case. Or maybe 
    // consider Around and AroundTimeout https://etorreborre.github.io/specs2/guide/SPECS2-3.6.2/org.specs2.guide.TimeoutExamples.html
    Thread.sleep(20 * 1000) // FIXME this could be a global timeout, or better something that looks if data is being sent or not
                             // maybe be could have a counter of test cases currently running
    logger.warn("stopping spark streaming context")
    maybeSsc. get. stop(stopSparkContext=false, stopGracefully=false)
    maybeSsc = None
  }
  
  /** Number of test case that will be executed in parallel on the same DStream
   * */
  def testCaseMultiplexing = 30 // 10
  
  def is = 
    sequential ^
    "Spark Streaming and ScalaCheck tests should" ^
    "use a proxy actor receiver to send data to a dstream in parallel"  ! actorSendingProp
   
  // val dsgenSeqSeq1 = Gen.listOfN(3, Gen.listOfN(2, Gen.choose(1, 100)))
    val dsgenSeqSeq1 = Gen.listOfN(50, Gen.listOfN(30, Gen.choose(1, 100)))
  //val dsgenSeqSeq1 = Gen.listOfN(30, Gen.listOfN(50, Gen.choose(1, 100)))

  // FIXME: move to suitable place
   /** Treats prefixDstream as a finite Spark DStream, i.e. as a sequence of batches, 
    *  and sends each batch each time Spark completes the processing of a batch. 
    *  This call is not blocking, as it simply registers a streaming listener 
    *  that onBatchCompleted sends all the elements of the current element of prefixDstream 
    *  and then moves to the next element
   */
  def sendBatchesOnBatchCompleteMaxParallel[A](ssc : StreamingContext, 
		  								       proxyReceiverActor : ActorSelection,
		  									   prefixDstream : Seq[Seq[A]]) : Unit = {
    ssc.addStreamingListener(new StreamingListener {
      var batches = prefixDstream 
      override def onBatchCompleted(batchCompleted: StreamingListenerBatchCompleted) : Unit =  {
        if (batches.length > 0) {
          val batch = batches(0)
          batches = batches.tail
          // FIXME: use debug instead
          logger.info(s"sending to proxy actor $proxyReceiverActor new batch ${batch.mkString(", ")}")
          batch. foreach(proxyReceiverActor ! _)
        }
      }
    })
  }
    
  def actorSendingProp = {
    logger.warn("creating Streaming Context")
    val ssc = maybeSsc.get
    val receiverActorName = "actorDStream1"
    val (proxyReceiverActor , actorInputDStream) = 
      (ProxyReceiverActor.getActorSelection(receiverActorName), 
       ProxyReceiverActor.createActorDStream[Int](ssc, receiverActorName))
    // actorInputDStream.print()
    actorInputDStream.map(_+1).map(x => (x % 5, 1)).reduceByKey(_+_).print()
    
    // TODO: assertions go here before the streaming context is started
    ssc.start()
    
    // wait for the receiver to start before sending data, otherwise the 
    // first batches are lost because we are using ! to send the data to the actor
    StreamingContextUtils.awaitUntilReceiverStarted(ssc, atMost = 5 seconds)
    logger.warn("detected that receiver has started")
    
      // TODO: with a sequential code it's now easier to get and use a test id
    var currentTestCaseId = TestCaseId.next() 
    
    val onBatchCompletedSyncVar = new SyncVar[Unit]
    ssc.addStreamingListener(new StreamingListener {
       override def onBatchCompleted(batchCompleted: StreamingListenerBatchCompleted) : Unit =  {
         // signal the property about the completion of a new batch  
         if (! onBatchCompletedSyncVar.isSet) {
           // note only this threads makes puts, so no problem with 
           // concurrency
           onBatchCompletedSyncVar.put(())
         }
       }
    })
    
    val _testCaseMultiplexing = testCaseMultiplexing
    var multiplexedTestCases : List[Seq[Seq[Int]]] = Nil
    // using AsResult explicitly to be independent from issue #393
    val thisProp = AsResult { Prop.forAll ("pdstream" |: dsgenSeqSeq1) { prefixDstream : Seq[Seq[Int]] =>
      // FIXME This implies some tests cases are lost ==> TODO add a final cleanup of multiplexedTestCases 
      // after thisProp, and make an and of that result with thisProp. See below 
      if (multiplexedTestCases.length < _testCaseMultiplexing) {
        // pick more generated test cases
        logger.warn(s"adding new test case to multiplexedTestCases: now len is ${multiplexedTestCases.length}")
        multiplexedTestCases = multiplexedTestCases :+ prefixDstream  
        logger.warn(s"now multiplexedTestCases.length = ${multiplexedTestCases.length}")
      } else {
        // only go to the next test case when we have consumed all the batches
        // of one of the test cases in multiplexedTestCases
        while (multiplexedTestCases.length == _testCaseMultiplexing) {
          // await for the end of a new batch to send more data
          logger.warn(s"await for the end of a new batch to send more data")
          onBatchCompletedSyncVar.take()
          // send new data and filter out completed test cases
          multiplexedTestCases = multiplexedTestCases map { batches =>
            val batch = batches.head
            // FIXME: use debug instead
            logger.warn(s"sending to proxy actor $proxyReceiverActor new batch ${batch.mkString(", ")}")
            batch. foreach(proxyReceiverActor ! _)
            batches.tail
          } filter {
            // filter out completed test cases
            _.length > 0
          }
        }
      }
      // TODO: put body of the while in a function sendCases and put here a 
      // while (multiplexedTestCases.length > 0) sendCases()
      
      // TODO: check sync of data sent with how data is checked: i.e. alignment of data
      // production and assertions. I think we get that from Spark if all the 
      // checks are performed as actions
      true
    }.set(workers = 1, minTestsOk = 150).verbose // NOTE: we always use 1 worker, and leave parallel execution to Spark => override user settings for that
  }

    // Returning thisProperty as the result for this example instead of ok or something like that 
    // is crucial for failing when the prop fails, even in  "thrown expectations" mode of Specs2
    // https://etorreborre.github.io/specs2/guide/SPECS2-3.6.2/org.specs2.guide.Structure.html
    // But this way the property its not executed until the end!, so we cannot stop the streaming
    // context in the property, but in a BeforeAfterEach
    thisProp 
  }
  
}