package es.ucm.fdi.sscheck.spark

import org.apache.spark._

/** This trait can be used to share a Spark Context. The context is created
 *  the first time sc() is called, and stopped when close() is called
 * */
trait SharedSparkContext
  extends Logging 
  with Serializable
  with java.io.Closeable {
  /** Override for custom config
  * */
  def sparkMaster : String = "local[4]"
  /** Override for custom config
  * */
  def sparkAppName : String = "scalacheck Spark test"
  
  // lazy val so early definitions are not needed for subtyping
  @transient lazy val conf = new SparkConf().setMaster(sparkMaster).setAppName(sparkAppName)    
  
  @transient protected[this] var _sc : Option[SparkContext] = None
  def sc() : SparkContext = { 
    _sc.getOrElse({
      logInfo("creating test Spark context")
      _sc = Some(new SparkContext(conf))
      _sc.get
    })
  }
  
  def close() : Unit = {
    _sc.foreach{sc => 
      logInfo("stopping test Spark context")
      sc.stop()
      // To avoid Akka rebinding to the same port, since it doesn't unbind immediately on shutdown
      System.clearProperty("spark.driver.port")
    }
    _sc = None
  }
}