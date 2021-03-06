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

package ch.chuv.lren.woken.service

import akka.NotUsed
import akka.stream.{ FlowShape, OverflowStrategy }
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Merge, Source }
import cats.effect.Effect
import cats.implicits._
import ch.chuv.lren.woken.core.fp._
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, QueryResult }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil.Validation
import com.typesafe.scalalogging.{ LazyLogging, Logger }
import ch.chuv.lren.woken.backends.woken.WokenClientService
import ch.chuv.lren.woken.core.model.VariablesMeta
import ch.chuv.lren.woken.messages.datasets.{ Dataset, DatasetId }
import ch.chuv.lren.woken.messages.remoting.RemoteLocation
import ch.chuv.lren.woken.messages.variables.{
  VariableMetaData,
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}

import scala.language.higherKinds

trait DispatcherService {

  type VariablesForDatasetsQR = (VariablesForDatasetsQuery, VariablesForDatasetsResponse)

  def localDatasets: Set[DatasetId]

  def dispatchTo(dataset: DatasetId): Option[RemoteLocation]

  def dispatchTo(datasets: Set[DatasetId]): (Set[RemoteLocation], Boolean)

  def dispatchRemoteMiningFlow: Flow[MiningQuery, (RemoteLocation, QueryResult), NotUsed]

  def dispatchRemoteExperimentFlow: Flow[ExperimentQuery, (RemoteLocation, QueryResult), NotUsed]

  def dispatchVariablesQueryFlow[F[_]: Effect](
      datasetService: DatasetService,
      variablesMetaService: VariablesMetaService[F]
  ): Flow[VariablesForDatasetsQuery, VariablesForDatasetsQR, NotUsed]

}

/**
  * Creates flows that dispatch queries to local or remote Woken workers according to the datasets
  *
  * @param allDatasets All datasets known to this Woken instance
  * @param wokenClientService The client service used to dispatch calls to other Woken instances
  *
  * @author Ludovic Claude <ludovic.claude@chuv.ch>
  */
class DispatcherServiceImpl(val allDatasets: Map[DatasetId, Dataset],
                            val wokenClientService: WokenClientService)
    extends DispatcherService
    with LazyLogging {

  override lazy val localDatasets: Set[DatasetId] = allDatasets.filter {
    case (_, dataset) => dataset.location.isEmpty
  }.keySet

  override def dispatchTo(dataset: DatasetId): Option[RemoteLocation] =
    if (allDatasets.isEmpty)
      None
    else
      allDatasets.get(dataset).flatMap(_.location)

  override def dispatchTo(datasets: Set[DatasetId]): (Set[RemoteLocation], Boolean) = {
    logger.info(s"Dispatch to datasets $datasets knowing $allDatasets")
    val maybeLocations = datasets.map(dispatchTo)
    val local          = maybeLocations.isEmpty || maybeLocations.contains(None)
    val maybeSet =
      maybeLocations.filter(_.nonEmpty).toList.sequence[Option, RemoteLocation].map(_.toSet)

    (maybeSet.getOrElse(Set.empty), local)
  }

  override def dispatchRemoteMiningFlow: Flow[MiningQuery, (RemoteLocation, QueryResult), NotUsed] =
    Flow[MiningQuery]
      .map(q => dispatchTo(q.datasets)._1.map(ds => ds -> q))
      .mapConcat(identity)
      .buffer(100, OverflowStrategy.backpressure)
      .map { case (l, q) => l.copy(url = l.url.withPath(l.url.path / "mining" / "job")) -> q }
      .via(wokenClientService.queryFlow)
      .named("dispatch-remote-mining")

  override def dispatchRemoteExperimentFlow
    : Flow[ExperimentQuery, (RemoteLocation, QueryResult), NotUsed] =
    Flow[ExperimentQuery]
      .map(q => dispatchTo(q.trainingDatasets)._1.map(ds => ds -> q))
      .mapConcat(identity)
      .buffer(100, OverflowStrategy.backpressure)
      .map {
        case (l, q) => l.copy(url = l.url.withPath(l.url.path / "mining" / "experiment")) -> q
      }
      .via(wokenClientService.queryFlow)
      .named("dispatch-remote-experiment")

  override def dispatchVariablesQueryFlow[F[_]: Effect](
      datasetService: DatasetService,
      variablesMetaService: VariablesMetaService[F]
  ): Flow[VariablesForDatasetsQuery, VariablesForDatasetsQR, NotUsed] =
    Flow.fromGraph(GraphDSL.create() { implicit builder =>
      import GraphDSL.Implicits._

      val broadcast = builder.add(Broadcast[VariablesForDatasetsQuery](2))
      val merger    = builder.add(Merge[VariablesForDatasetsQR](2))

      broadcast ~> remoteDispatchVariablesQueryFlow() ~> merger
      broadcast ~> localVariablesQueryFlow(datasetService, variablesMetaService) ~> merger

      FlowShape(broadcast.in, merger.out)
    })

  def remoteDispatchVariablesQueryFlow()
    : Flow[VariablesForDatasetsQuery, VariablesForDatasetsQR, NotUsed] =
    Flow[VariablesForDatasetsQuery]
      .map(q => {
        val target = dispatchTo(q.datasets)
        logger.info(s"Target datasets $target")
        target._1.map(location => location -> q)
      })
      .mapConcat(identity)
      .buffer(100, OverflowStrategy.backpressure)
      .map {
        case (l, q) =>
          l.copy(url = l.url.withPath(l.url.path / "metadata" / "variables")) -> q
      }
      .via(wokenClientService.variableMetaFlow)
      .map(r => (r._2, r._3))

  /**
    * Return the flow that handles locally queries for 'variables for datasets'
    *
    * @param datasetService Service that provides information about known datasets
    * @param variablesMetaService Service that provides the metadata for the variables
    */
  def localVariablesQueryFlow[F[_]: Effect](
      datasetService: DatasetService,
      variablesMetaService: VariablesMetaService[F]
  ): Flow[VariablesForDatasetsQuery, VariablesForDatasetsQR, NotUsed] =
    Flow[VariablesForDatasetsQuery]
      .map[VariablesForDatasetsQR] { q =>
        val datasets: Set[Dataset] = datasetService
          .datasets()
          .filter(_.location.isEmpty)
          .filter(ds => q.datasets.isEmpty || q.datasets.contains(ds.dataset))
        val mergedVariables = datasets
          .map { ds =>
            val varsForDs = ds.tables
              .map(_.toUpperCase) // TODO: table name should not always be uppercase
              .flatMap(v => runNow(variablesMetaService.get(v)))
              .flatMap(_.filterVariables(_ => true))
              .map(_.copy(datasets = Set(ds.dataset)))
            varsForDs.toSet
          }
          .foldLeft(Set[VariableMetaData]()) {
            case (vars, toMerge) => VariablesMeta.merge(vars, toMerge, q.exhaustive)
          }
        (q, VariablesForDatasetsResponse(mergedVariables, None))
      }

  def localDispatchFlow(datasets: Set[DatasetId]): Source[QueryResult, NotUsed] = ???

}

object DispatcherService {

  private val logger = Logger("DispatcherService")

  private[service] def loadDatasets(
      datasets: Validation[Map[DatasetId, Dataset]]
  ): Map[DatasetId, Dataset] =
    datasets.fold({ e =>
      logger.info(s"No datasets configured: $e")
      Map[DatasetId, Dataset]()
    }, identity)

  def apply(datasets: Validation[Map[DatasetId, Dataset]],
            wokenService: WokenClientService): DispatcherService =
    new DispatcherServiceImpl(loadDatasets(datasets), wokenService)

}
