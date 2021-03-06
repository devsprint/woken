/*
 * Copyright (C) 2017  LREN CHUV for Human Brain Project
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package ch.chuv.lren.woken.dispatch

import java.time.OffsetDateTime

import akka.NotUsed
import akka.actor.SupervisorStrategy.Restart
import akka.actor.{ Actor, ActorRef, OneForOneStrategy, Props }
import akka.routing.{ OptimalSizeExploringResizer, RoundRobinPool }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import cats.effect.Effect
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.mining._
import ch.chuv.lren.woken.core.model.UserFeedbacks
import ch.chuv.lren.woken.core.model.jobs._
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import ch.chuv.lren.woken.messages.query._
import ch.chuv.lren.woken.messages.validation.Score
import ch.chuv.lren.woken.messages.validation.validationProtocol._
import ch.chuv.lren.woken.service.{ BackendServices, DatabaseServices }
import ch.chuv.lren.woken.validation.flows.ValidationFlow
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.{ higherKinds, postfixOps }
import scala.util.{ Failure, Success }
import spray.json._

object MiningQueriesActor extends LazyLogging {

  case class Mine(query: MiningQuery, replyTo: ActorRef)

  def props[F[_]: Effect](config: WokenConfiguration,
                          databaseServices: DatabaseServices[F],
                          backendServices: BackendServices): Props =
    Props(
      new MiningQueriesActor(config, databaseServices, backendServices)
    )

  def roundRobinPoolProps[F[_]: Effect](config: WokenConfiguration,
                                        databaseServices: DatabaseServices[F],
                                        backendServices: BackendServices): Props = {

    val resizer = OptimalSizeExploringResizer(
      config.config
        .getConfig("poolResizer.miningQueries")
        .withFallback(
          config.config.getConfig("akka.actor.deployment.default.optimal-size-exploring-resizer")
        )
    )
    val miningSupervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
        case e: Exception =>
          logger.error("Error detected in Mining queries actor, restarting", e)
          Restart
      }

    RoundRobinPool(
      1,
      resizer = Some(resizer),
      supervisorStrategy = miningSupervisorStrategy
    ).props(
      MiningQueriesActor.props(config, databaseServices, backendServices)
    )
  }

}

class MiningQueriesActor[F[_]: Effect](
    override val config: WokenConfiguration,
    override val databaseServices: DatabaseServices[F],
    override val backendServices: BackendServices
) extends QueriesActor[MiningQuery, F] {

  import MiningQueriesActor.Mine

  private val validationFlow
    : Flow[ValidationJob[F], (ValidationJob[F], Either[String, Score]), NotUsed] =
    ValidationFlow(
      CoordinatorActor.executeJobAsync(coordinatorConfig, context),
      context
    ).validate()

  @SuppressWarnings(
    Array("org.wartremover.warts.Any",
          "org.wartremover.warts.NonUnitStatements",
          "org.wartremover.warts.Product")
  )
  override def receive: Receive = {

    case mine: Mine =>
      val initiator     = if (mine.replyTo == Actor.noSender) sender() else mine.replyTo
      val query         = mine.query
      val jobValidatedF = queryToJobService.miningQuery2Job(query)

      runNow(jobValidatedF)(processJob(initiator, query))

    case CoordinatorActor.Response(job, List(errorJob: ErrorJobResult), initiator) =>
      logger.warn(s"Received error while mining ${job.query}: ${errorJob.toString}")
      // TODO: we lost track of the original query here
      initiator ! errorJob.asQueryResult(None)

    case CoordinatorActor.Response(job, results, initiator) =>
      // TODO: we can only handle one result from the Coordinator handling a mining query.
      // Containerised algorithms that can produce more than one result (e.g. PFA model + images) are ignored
      logger.info(
        s"Send back results for mining ${job.query}: ${results.toString.take(50)} to $initiator"
      )
      val jobResult = results.head
      // TODO: we lost track of the original query here
      initiator ! jobResult.asQueryResult(None)

    case e =>
      logger.warn(s"Received unhandled request $e of type ${e.getClass}")

  }

  private def processJob(initiator: ActorRef, query: MiningQuery)(
      cb: Either[Throwable, Validation[(Job, UserFeedbacks)]]
  ): Unit = cb match {
    case Left(e) =>
      logger.error("", e)
      reportErrorMessage(query, initiator)(
        s"Mining for $query failed with error: ${e.toString}"
      )
    case Right(jobValidated) =>
      jobValidated.fold(
        errorMsg => {
          reportErrorMessage(query, initiator)(
            s"Mining for $query failed with message: " + errorMsg.reduceLeft(_ + ", " + _)
          )
        }, {
          // TODO: report feedback to user
          case (job: DockerJob, feedback) =>
            if (feedback.nonEmpty) logger.info(s"Feedback: ${feedback.mkString(", ")}")
            runMiningJob(query, initiator, job)
          case (job: ValidationJob[F], feedback) =>
            if (feedback.nonEmpty) logger.info(s"Feedback: ${feedback.mkString(", ")}")
            runValidationJob(initiator, job)
          case (job, _) =>
            reportErrorMessage(query, initiator)(
              s"Unsupported job $job. Was expecting a job of type DockerJob or ValidationJob"
            )
        }
      )
  }

  private def runMiningJob(query: MiningQuery, initiator: ActorRef, job: DockerJob): Unit = {

    // Detection of histograms in federation mode
    val forceLocal = query.algorithm.code == "histograms"

    if (forceLocal) {
      logger.info(s"Local data mining for query $query")
      startMiningJob(job, initiator)
    } else
      dispatcherService.dispatchTo(query.datasets) match {

        // Local mining on a worker node or a standalone node
        case (_, true) =>
          logger.info(s"Local data mining for query $query")
          startMiningJob(job, initiator)

        // Mining from the central server using one remote node
        case (remoteLocations, false) if remoteLocations.size == 1 =>
          logger.info(s"Remote data mining on a single node $remoteLocations for query $query")
          mapFlow(query)
            .mapAsync(1) {
              case List()        => Future(noResult(query))
              case List(result)  => Future(result.copy(query = Some(query)))
              case listOfResults => gatherAndReduce(query, listOfResults, None)
            }
            .map(reportResult(initiator))
            .log("Result of experiment")
            .runWith(Sink.last)
            .failed
            .foreach(reportError(query, initiator))

        // Execution of the experiment from the central server in a distributed mode
        case (remoteLocations, _) =>
          logger.info(s"Remote data mining on nodes $remoteLocations for query $query")
          val algorithm           = job.algorithmSpec
          val algorithmDefinition = job.algorithmDefinition
          val queriesByStepExecution: Map[ExecutionStyle.Value, MiningQuery] =
            algorithmDefinition.distributedExecutionPlan.steps.map { step =>
              (step.execution,
               query.copy(covariablesMustExist = true,
                          algorithm = algorithm.copy(step = Some(step))))
            }.toMap

          val mapQuery = queriesByStepExecution.getOrElse(ExecutionStyle.map, query)
          val reduceQuery =
            queriesByStepExecution.get(ExecutionStyle.reduce).map(_.copy(datasets = Set()))

          mapFlow(mapQuery)
            .mapAsync(1) {
              case List()       => Future(noResult(query))
              case List(result) => Future(result.copy(query = Some(query)))
              case mapResults   => gatherAndReduce(query, mapResults, reduceQuery)
            }
            .map(reportResult(initiator))
            .runWith(Sink.last)
            .failed
            .foreach(reportError(query, initiator))
      }
  }

  private def mapFlow(mapQuery: MiningQuery) =
    Source
      .single(mapQuery)
      .via(dispatcherService.dispatchRemoteMiningFlow)
      .fold(List[QueryResult]()) {
        _ :+ _._2
      }

  private def startMiningJob(job: DockerJob, initiator: ActorRef): Unit = {
    val miningActorRef = newCoordinatorActor
    miningActorRef ! StartCoordinatorJob(job, self, initiator)
  }

  private[dispatch] def newCoordinatorActor: ActorRef =
    context.actorOf(CoordinatorActor.props(coordinatorConfig))

  private def runValidationJob(initiator: ActorRef, job: ValidationJob[F]): Unit =
    dispatcherService.dispatchTo(job.query.datasets) match {
      case (_, true) =>
        Source
          .single(job)
          .via(validationFlow)
          .runWith(Sink.last)
          .andThen {
            case Success((job: ValidationJob[F], Right(score))) =>
              initiator ! QueryResult(
                Some(job.jobId),
                coordinatorConfig.jobsConf.node,
                OffsetDateTime.now(),
                Shapes.score,
                Some(ValidationJob.algorithmCode),
                Some(score.toJson),
                None,
                Some(job.query)
              )
            case Success((job: ValidationJob[F], Left(error))) =>
              initiator ! QueryResult(
                Some(job.jobId),
                coordinatorConfig.jobsConf.node,
                OffsetDateTime.now(),
                Shapes.error,
                Some(ValidationJob.algorithmCode),
                None,
                Some(error),
                Some(job.query)
              )
            case Failure(t) =>
              initiator ! QueryResult(
                Some(job.jobId),
                coordinatorConfig.jobsConf.node,
                OffsetDateTime.now(),
                Shapes.error,
                Some(ValidationJob.algorithmCode),
                None,
                Some(t.toString),
                Some(job.query)
              )

          }

      case _ =>
        logger.info(
          s"No local datasets match the validation query, asking for datasets ${job.query.datasets.mkString(",")}"
        )
        // No local datasets match the query, return an empty result
        initiator ! QueryResult(
          Some(job.jobId),
          coordinatorConfig.jobsConf.node,
          OffsetDateTime.now(),
          Shapes.score,
          Some(ValidationJob.algorithmCode),
          None,
          None,
          Some(job.query)
        )
    }

  private[dispatch] def reduceUsingJobs(query: MiningQuery, jobIds: List[String]): MiningQuery =
    query.copy(algorithm = addJobIds(query.algorithm, jobIds))

  override private[dispatch] def wrap(query: MiningQuery, initiator: ActorRef) =
    Mine(query, initiator)

}
