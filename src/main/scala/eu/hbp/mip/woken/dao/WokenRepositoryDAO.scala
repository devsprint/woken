/*
 * Copyright 2017 Human Brain Project MIP by LREN CHUV
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.hbp.mip.woken.dao

import java.time.{ OffsetDateTime, ZoneOffset }

import doobie._
import doobie.implicits._
import cats._
import eu.hbp.mip.woken.core.model.Shapes.{ error => errorShape, _ }
import eu.hbp.mip.woken.core.model._
import eu.hbp.mip.woken.json.yaml
import eu.hbp.mip.woken.json.yaml.Yaml
import org.slf4j.LoggerFactory
import spray.json._

import scala.language.higherKinds
import scala.util.Try

class WokenRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends WokenRepository[F] {

  override val jobResults: JobResultRepositoryDAO[F] = new JobResultRepositoryDAO[F](xa)

}

/**
  * Interpreter based on Doobie that provides the operations of the algebra
  */
class JobResultRepositoryDAO[F[_]: Monad](val xa: Transactor[F]) extends JobResultRepository[F] {

  private val logger = LoggerFactory.getLogger("JobResultRepositoryDAO")

  private implicit val DateTimeMeta: Meta[OffsetDateTime] =
    Meta[java.sql.Timestamp].xmap(ts => OffsetDateTime.of(ts.toLocalDateTime, ZoneOffset.UTC),
                                  dt => java.sql.Timestamp.valueOf(dt.toLocalDateTime))
  implicit val JsObjectMeta: Meta[JsObject] = DAL.JsObjectMeta

  type JobResultColumns =
    (String, String, OffsetDateTime, String, String, Option[String], Option[String])

  private val unsafeFromColumns: JobResultColumns => JobResult = {
    case (jobId, node, timestamp, shape, function, _, Some(errorMessage))
        if errorShape.contains(shape) =>
      ErrorJobResult(jobId, node, timestamp, function, errorMessage)
    case (jobId, node, timestamp, _, function, data, Some(errorMessage))
        if data.isEmpty && errorMessage.trim.nonEmpty =>
      ErrorJobResult(jobId, node, timestamp, function, errorMessage)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if pfa.contains(shape) =>
      Try(
        PfaJobResult(jobId, node, timestamp, function, data.parseJson.asJsObject)
      ).getOrElse {
        val msg = s"Data for job $jobId produced by $function is not a valid Json object"
        logger.warn(msg)
        ErrorJobResult(jobId, node, timestamp, function, msg)
      }
    case (jobId, node, timestamp, shape, _, Some(data), None) if pfaExperiment.contains(shape) =>
      Try(
        PfaExperimentJobResult(jobId, node, timestamp, data.parseJson.asInstanceOf[JsArray])
      ).getOrElse {
        val msg = s"Data for job $jobId for a PFA experiment is not a valid Json array"
        logger.warn(msg)
        ErrorJobResult(jobId, node, timestamp, "experiment", msg)
      }
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if pfaYaml.contains(shape) =>
      PfaJobResult(jobId, node, timestamp, function, yaml.yaml2Json(Yaml(data)).asJsObject)
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if getVisualisationJson(shape).isDefined =>
      Try {
        val json = data.parseJson
        JsonDataJobResult(jobId,
                          node,
                          timestamp,
                          getVisualisationJson(shape).get.mime,
                          function,
                          json)
      }.getOrElse(
        ErrorJobResult(jobId,
                       node,
                       timestamp,
                       function,
                       s"Data for job $jobId produced by $function is not a valid Json document")
      )
    case (jobId, node, timestamp, shape, function, Some(data), None | Some(""))
        if getVisualisationOther(shape).isDefined =>
      OtherDataJobResult(jobId,
                         node,
                         timestamp,
                         getVisualisationOther(shape).get.mime,
                         function,
                         data)
    case (jobId, node, timestamp, shape, function, data, error) =>
      val msg =
        s"Cannot handle job results of shape $shape produced by function $function with data $data, error $error"
      logger.warn(msg)
      ErrorJobResult(jobId, node, timestamp, function, msg)
  }

  private val jobResultToColumns: JobResult => JobResultColumns = {
    case j: PfaJobResult =>
      (j.jobId, j.node, j.timestamp, pfa.mime, j.function, Some(j.model.compactPrint), None)
    case j: PfaExperimentJobResult =>
      val pfa = j.models.compactPrint
      (j.jobId, j.node, j.timestamp, pfaExperiment.mime, j.function, Some(pfa), None)
    case j: ErrorJobResult =>
      (j.jobId, j.node, j.timestamp, errorShape.mime, j.function, None, Some(j.error))
    case j: JsonDataJobResult =>
      (j.jobId, j.node, j.timestamp, j.shape, j.function, Some(j.data.compactPrint), None)
    case j: OtherDataJobResult =>
      (j.jobId, j.node, j.timestamp, j.shape, j.function, Some(j.data), None)
  }

  private implicit val JobResultComposite: Composite[JobResult] =
    Composite[JobResultColumns].imap(unsafeFromColumns)(jobResultToColumns)

  override def get(jobId: String): F[Option[JobResult]] =
    sql"select job_id, node, timestamp, shape, function, data, error from job_result where job_id = $jobId"
      .query[JobResult]
      .option
      .transact(xa)

  override def put(jobResult: JobResult): F[JobResult] = {
    val update: Update0 = jobResult match {
      case ErrorJobResult(jobId, node, timestamp, function, error) =>
        sql"""
          INSERT INTO job_result (job_id, node, timestamp, shape, function, data, error)
                 VALUES ($jobId, $node, $timestamp, ${errorShape.mime}, $function, NULL, $error)
          """.update
      case PfaJobResult(jobId, node, timestamp, function, model) =>
        sql"""
          INSERT INTO job_result (job_id, node, timestamp, shape, function, data, error)
                 VALUES ($jobId, $node, $timestamp, ${pfa.mime}, $function, ${model.compactPrint}, NULL)
          """.update
      case PfaExperimentJobResult(jobId, node, timestamp, models) =>
        sql"""
          INSERT INTO job_result (job_id, node, timestamp, shape, function, data, error)
                 VALUES ($jobId, $node, $timestamp, ${pfaExperiment.mime}, "experiment", ${models.compactPrint}, NULL)
          """.update
      case e => throw new IllegalArgumentException("Unsupported type of JobResult: $e")
    }
    update
      .withUniqueGeneratedKeys[JobResult]("job_id",
                                          "node",
                                          "timestamp",
                                          "shape",
                                          "function",
                                          "data",
                                          "error")
      .transact(xa)
  }

}
