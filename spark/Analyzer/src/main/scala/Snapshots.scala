package analyzer

import org.apache.spark.sql.Dataset
import org.apache.spark.sql.DataFrame


import org.apache.spark.sql.functions.udf
import org.apache.spark.sql.functions.avg
import org.apache.spark.sql.functions.first
import org.apache.spark.sql.functions.lit
import org.apache.spark.sql.functions.count
import org.apache.spark.sql.functions.stddev
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.functions.explode



object Snapshots{
  
  def getSnapshots( chronicles: Dataset[ChronicleEntry], maxTime: Double ): DataFrame = {
    val snapshotsUdf = udf( 
      (t1:Double, t2:Double) => (0d to maxTime by 1).filter( t => t1 < t && t < t2 ) 
    )
    chronicles.withColumn("timePoint", explode(snapshotsUdf(col("birthTime"), col("deathTime"))))
  }

  def mutationSnapshots( snapshots: DataFrame ): DataFrame = {
      snapshots.
        groupBy("timePoint","mutationId").
        agg(
          count(lit(1)), 
          first("mutation.birthEfficiency").alias("birth_efficiency"), 
          first("mutation.birthResistance").alias("birth_resistance"), 
          first("mutation.lifespanEfficiency").alias("lifespan_efficiency"), 
          first("mutation.lifespanResistance").alias("lifespan_resistance"), 
          first("mutation.successEfficiency").alias("success_efficiency"), 
          first("mutation.successResistance").alias("success_resistance")
        )
  }

  def getTimeStats( chronicleEntries: Dataset[ChronicleEntry], maxTime: Double ): DataFrame = {
    
    val timeStats = chronicleEntries.groupBy("timePoint").agg(
      count(lit(1)).alias("count"), 
      //max( sqrt( $"position_0"*$"position_0" + $"position_1"*$"position_1" + $"position_2"*$"position_2")),
      // means
      avg("mutation.birthEfficiency").alias("mean_birth_efficiency"), 
      avg("mutation.birthResistance").alias("mean_birth_resistance"), 
      avg("mutation.lifespanEfficiency").alias("mean_lifespan_efficiency"), 
      avg("mutation.lifespanResistance").alias("mean_lifespan_resistance"), 
      avg("mutation.successEfficiency").alias("mean_sucdess_efficiency"), 
      avg("mutation.successResistance").alias("mean_success_resistance"),

      // stdev
      stddev("mutation.birthEfficiency").alias("stddev_birth_efficiency"), 
      stddev("mutation.birthResistance").alias("stddev_birth_resistance"), 
      stddev("mutation.lifespanEfficiency").alias("stddev_lifespan_efficiency"), 
      stddev("mutation.lifespanResistance").alias("stddev_lifespan_resistance"), 
      stddev("mutation.successEfficiency").alias("stddev_sucdess_efficiency"), 
      stddev("mutation.successResistance").alias("stddev_success_resistance")
    ).orderBy("timePoint")
    
    timeStats;
  }
  
  def getFinal( chronicleEntries: Dataset[ChronicleEntry]): DataFrame = {
    // final
    chronicleEntries.filter( col("deathTime") === Double.PositiveInfinity ).
      select(
        "position.x", "position.y", "position.z", 
        "mutationId",
        "mutation.birthEfficiency", "mutation.birthResistance", 
        "mutation.lifespanEfficiency", "mutation.lifespanResistance", 
        "mutation.successEfficiency", "mutation.successResistance"
      )
  }
  
  def writeSnapshots( chronicles: Dataset[ChronicleLine], pathPrefix: String, maxTime: Double ) = {
    // snapshots
    for( t <- (0d to maxTime by 1d) ){
      chronicles.
      filter( (col("birth_time") < lit(t) ) && (col("death_time") > lit(t) )  ).
      coalesce(1).
      write.
      format("csv").
      option("delimiter",";").
      option("header", "true").
      mode("overwrite").
      save(pathPrefix + "/snapshots/" + t.toString() )  
    }
  }
}