package ca.mcit.bigdata.sprint3

import io.confluent.kafka.serializers.KafkaAvroSerializer
import org.apache.avro.Schema
import org.apache.avro.generic.{GenericRecord, GenericRecordBuilder}
import org.apache.kafka.clients.consumer.{ConsumerConfig, ConsumerRecord}
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.{StringDeserializer, StringSerializer}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{Row, SaveMode, SparkSession}
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.streaming.kafka010.{ConsumerStrategies, KafkaUtils, LocationStrategies}
import org.apache.spark.streaming.{Seconds, StreamingContext}

import java.util.Properties

object SparkEnrichment extends App with Config {

  val spark = SparkSession.builder()
    .appName("Sprint 3 Project")
    .master("local[*]")
    .getOrCreate()

  val ssc = new StreamingContext(spark.sparkContext, Seconds(10))

  val fileEnrichedStation = "D:\\Project Bigdata\\Code\\Sprint3\\Data\\enriched_station_information.csv"
  val stationInformationRdd = spark.sparkContext.textFile(fileEnrichedStation).map(fromCsv => EnrichedStationsInfo(fromCsv))

  import spark.implicits._

  val stationInformationDf = stationInformationRdd.toDF()

  val kafkaConfig = Map[String, String](
    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG -> "localhost:9092",
    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG -> classOf[StringDeserializer].getName,
    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG -> classOf[StringDeserializer].getName,
    ConsumerConfig.GROUP_ID_CONFIG -> "100-trips",
    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG -> "earliest",
    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG -> "false")

  val topic = "bdss2001_vish_trip"

  val inStream: DStream[ConsumerRecord[String, String]] = KafkaUtils.createDirectStream[String, String](
    ssc,
    LocationStrategies.PreferConsistent,
    ConsumerStrategies.Subscribe[String, String](List(topic), kafkaConfig))

  val inStreamValues: DStream[String] = inStream.map(_.value())

  inStreamValues.foreachRDD(rdd => processAndEnrichment(rdd))

  val producerProperties = new Properties()
  producerProperties.setProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092")
  producerProperties.setProperty(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, classOf[StringSerializer].getName)
  producerProperties.setProperty(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, classOf[KafkaAvroSerializer].getName)
  producerProperties.setProperty("schema.registry.url", "localhost:8081")

  def enrichedTripAvro(row: Row, enrichedTripSchema: Schema): GenericRecord = {
    new GenericRecordBuilder(enrichedTripSchema)
      .set("start_date", row.getAs[String](0))
      .set("start_station_code", row.getAs[Int](1))
      .set("end_date", row.getAs[String](2))
      .set("end_station_code", row.getAs[Int](3))
      .set("duration_sec", row.getAs[Int](4))
      .set("is_member", row.getAs[Int](5))
      .set("system_id", row.getAs[String](6))
      .set("timezone", row.getAs[String](7))
      .set("station_id", row.getAs[Int](8))
      .set("name", row.getAs[String](9))
      .set("short_name", row.getAs[String](10))
      .set("lat", row.getAs[Double](11))
      .set("lon", row.getAs[Double](12))
      .set("capacity", row.getAs[Int](13))
      .build()
  }

  def processAndEnrichment(rdd: RDD[String]) = {
    val trips: RDD[Trip] = rdd.map(fromCsv => Trip(fromCsv))
    val tripsDf = trips.toDF()
    tripsDf.createOrReplaceTempView("trip")
    stationInformationDf.createOrReplaceTempView("station")

    val joinResult = spark.sql(
      """
        |SELECT *
        |FROM trip JOIN station ON trip.start_station_code = station.short_name
        |""".stripMargin)

    joinResult.coalesce (1).write.mode (SaveMode.Append)
      .csv ("D:\\Project Bigdata\\Code\\Sprint3\\Data\\enriched_trip")
  }

  ssc.start()
  ssc.awaitTermination()
  ssc.stop(stopSparkContext = true, stopGracefully = true)
  spark.close()
}