package analyzer

import analyzer.expression.functions.sequentialGroup

import org.apache.spark.sql.catalyst.expressions.{Add, AggregateWindowFunction, AttributeReference, Expression, If, Literal}
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.types.{DataType, IntegerType, LongType}
import org.apache.spark.sql._

import scala.collection.immutable.NumericRange

object Chronicler {

  val PARTITION_MASK_SHIFT = 33

  val LIT_CREATED: Column = struct(lit(1).as("encoded"))
  val LIT_REMOVED: Column = struct(lit(2).as("encoded"))
  val LIT_TRANSFORMED: Column = struct(lit(4).as("encoded"))

  val startingMutationId: Long = 1
  val startingMutation = Mutation(0.1f, 0.5f, 0.1f, 0.5f, 0.9f, 0.5f)
  val posRange: NumericRange[Float] = -2.0f to 2.0f by 1.0f


  def startingSnapshot(spark: SparkSession): Dataset[Cell] = {
    import spark.implicits._

    val starting = for (x <- posRange; y <- posRange; z <- posRange) yield
      Cell(
        Position(x, y, z),
        startingMutationId,
        startingMutation
      )
    starting.toDS
  }

  def groupEventsDF(events: Dataset[Event]): DataFrame /*Dataset[GroupedEvent]*/ = {

    val windowSpec = Window.partitionBy("partitionId").orderBy("timeOrder")
    events.
      withColumn("timeOrder", monotonically_increasing_id()).
      repartitionByRange(col("time")).
      sortWithinPartitions("timeOrder").
      withColumn("partitionId", spark_partition_id().cast(LongType)).
      withColumn("eventId",
       sequentialGroup(col("timeDelta")===0).over(windowSpec) + shiftLeft(col("partitionId"), PARTITION_MASK_SHIFT)
    )
  }
  def groupEvents(events: Dataset[Event]): Dataset[GroupedEvent] = {
    groupEventsDF(events).drop("timeOrder","partitionId").as(Encoders.product[GroupedEvent])
  }

  def computeLinearChronicles(initial: Dataset[Cell], groupedEvents: Dataset[GroupedEvent]): DataFrame = {

    val initialEvents: Dataset[GroupedEvent] = initial.
      withColumn("time", lit(Double.NegativeInfinity)).
      withColumn("timeDelta", monotonically_increasing_id().cast(IntegerType)).
      withColumn("eventKind", LIT_CREATED).
      withColumn("eventId", lit(0L)).
      as(Encoders.product[GroupedEvent])

    val enumeratedEvents: Dataset[GroupedEvent] = initialEvents unionByName groupedEvents

    val linearChronicles = enumeratedEvents.
      repartition(256, col("position")).
      withColumn("particleId", monotonically_increasing_id()+1).
      withColumn("parentId",
        lag("particleId", 1, null)
          over Window.partitionBy("position").orderBy("eventId")).
      filter(col("eventKind") =!= LIT_REMOVED).
      withColumn("deathTime",
        lead("time", 1, Double.PositiveInfinity)
          over Window.partitionBy("position").orderBy("eventId")).
      withColumnRenamed("time","birthTime")

    linearChronicles
  }
/*
  def computeChronicles(linearChronicles: DataFrame): Dataset[ChronicleEntry] = {

    val offspring = linearChronicles.
      filter(!isnull(col("parentId"))).
      alias("offspring")

    val settlers = linearChronicles.
      filter(isnull(col("parentId"))).
      drop("parentId").
      alias("settlers")

    val resolvedSettlers = offspring.
      select("eventId", "parentId").
      join(settlers, Seq("eventId"), "RIGHT")

    val chronicles = (resolvedSettlers unionByName offspring).
      select(
        col("particleId").as(Encoders.LONG),
        coalesce(col("parentId"), lit(0L)).as("parentId").as(Encoders.LONG),
        col("birthTime").as(Encoders.DOUBLE),
        col("deathTime").as(Encoders.DOUBLE),
        col("position").as(Encoders.product[Position]),
        col("mutationId").as(Encoders.LONG),
        col("mutation").as(Encoders.product[Mutation])
      ).as(Encoders.product[ChronicleEntry])

    chronicles
  }

  def computeChronicles(spark: SparkSession, events: Dataset[EnumeratedEvent], pathPrefix: String): Dataset[ChronicleEntry] ={
    //val events = StreamLoader.readEvents(spark, pathPrefix)
    val groupedEvents = groupEvents(events, singlePartition = true)
    val initialSnapshot: Dataset[Cell] = startingSnapshot(spark)

    val linearChronicles =
      computeLinearChronicles(initialSnapshot, groupedEvents).
        repartitionByRange(col("eventKind"))

    computeChronicles(linearChronicles)
  }

  def computeOrReadChronicles(spark: SparkSession, pathPrefix: String): Dataset[ChronicleEntry] =
  {
    import spark.implicits._
    var chronicles: Dataset[ChronicleEntry] = null
    try{
      chronicles = spark.read.parquet(pathPrefix + "/chronicles.parquet").as[ChronicleEntry]
    }catch {
      case e: Exception => {

        val events = StreamLoader.convertOrReadEvents(spark, pathPrefix)
        computeChronicles(spark, events, pathPrefix).
          write.
          mode("overwrite").
          mode(SaveMode.Overwrite).
          parquet(pathPrefix+"/chronicles.parquet")

        chronicles = spark.read.parquet(pathPrefix + "/chronicles.parquet").as[ChronicleEntry]
      }
    }
    chronicles
  }*/

  def main(args: Array[String]) {

    if (args.length != 1)
      throw new RuntimeException("no prefix path given")

    val pathPrefix = args(0)

    val spark = SparkSession.builder.
      appName("SimBaD analyzer").
      getOrCreate()

    spark.sparkContext.setCheckpointDir(pathPrefix + "/checkpoints/")

    val stream = StreamReader.readEventStreamLinesParquet(spark, pathPrefix)
    val events = StreamReader.toEvents(stream)
    val groupedEvents = groupEvents(events)
    val counts = groupedEvents.groupBy("position").count()

    counts.explain(true)
    //counts.describe("position", "count").show()
    counts.orderBy(desc("count")).show(200)
      /*
      write.
      mode(SaveMode.Overwrite).
      format("csv").
      option("delimiter", ";").
      option("header", true).
      save(pathPrefix+"/grouped_events.csv")*/

    scala.io.StdIn.readLine()
  }
}
