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
import akka.actor.{ ActorRef, ActorSystem }
import akka.stream.scaladsl.Flow
import cats.effect.{ ContextShift, Effect, IO, Timer }
import cats.effect.internals.IOContextShift
import ch.chuv.lren.woken.JsonUtils
import ch.chuv.lren.woken.akka.FakeActors
import ch.chuv.lren.woken.config.WokenConfiguration
import ch.chuv.lren.woken.core.model.{ FeaturesTableDescription, TableColumn, VariablesMeta }
import ch.chuv.lren.woken.core.model.database.TableId
import ch.chuv.lren.woken.dao.FeaturesTableRepository.Headers
import ch.chuv.lren.woken.dao.{
  FeaturesInMemoryRepository,
  FeaturesTableInMemoryRepository,
  MetadataInMemoryRepository,
  WokenInMemoryRepository
}
import ch.chuv.lren.woken.messages.datasets.DatasetId
import ch.chuv.lren.woken.messages.query.{ ExperimentQuery, MiningQuery, QueryResult }
import ch.chuv.lren.woken.messages.remoting.RemoteLocation
import ch.chuv.lren.woken.messages.variables.{
  GroupMetaData,
  SqlType,
  VariablesForDatasetsQuery,
  VariablesForDatasetsResponse
}
import ch.chuv.lren.woken.messages.variables.variablesProtocol._
import spray.json.{ JsNumber, JsObject, JsString }

import scala.concurrent.ExecutionContext
import scala.language.higherKinds

object TestServices extends JsonUtils {

  lazy val jobResultService: JobResultService[IO] = JobResultService(
    new WokenInMemoryRepository[IO]().jobResults
  )

  lazy val emptyVariablesMetaService: VariablesMetaService[IO] = {
    VariablesMetaService(
      new MetadataInMemoryRepository[IO]().variablesMeta
    )
  }

  lazy val localVariablesMetaService: VariablesMetaService[IO] = {
    val churnHierarchy = loadJson("/metadata/churn_variables.json").convertTo[GroupMetaData]
    val churnVariablesMeta =
      VariablesMeta(1, "churn", churnHierarchy, "CHURN", List("state", "custserv_calls", "churn"))

    val sampleHierarchy     = loadJson("/metadata/sample_variables.json").convertTo[GroupMetaData]
    val sampleVariablesMeta = VariablesMeta(2, "sample", sampleHierarchy, "Sample", Nil)

    val cdeHierarchy = loadJson("/metadata/mip_cde_variables.json").convertTo[GroupMetaData]
    val cdeGroupings = List("dataset", "gender", "agegroup", "alzheimerbroadcategory")
    val featuresAVariablesMeta =
      VariablesMeta(3, "cde_features_a", cdeHierarchy, "CDE_FEATURES_A", cdeGroupings)
    val featuresBVariablesMeta =
      VariablesMeta(4, "cde_features_b", cdeHierarchy, "CDE_FEATURES_B", cdeGroupings)
    val featuresCVariablesMeta =
      VariablesMeta(5, "cde_features_c", cdeHierarchy, "CDE_FEATURES_C", cdeGroupings)
    val featuresMixedVariablesMeta =
      VariablesMeta(6, "cde_features_mixed", cdeHierarchy, "CDE_FEATURES_MIXED", cdeGroupings)

    val metaService = VariablesMetaService(
      new MetadataInMemoryRepository[IO]().variablesMeta
    )

    metaService.put(churnVariablesMeta)
    metaService.put(sampleVariablesMeta)
    metaService.put(featuresAVariablesMeta)
    metaService.put(featuresBVariablesMeta)
    metaService.put(featuresCVariablesMeta)
    metaService.put(featuresMixedVariablesMeta)

    metaService
  }

  lazy val algorithmLibraryService: AlgorithmLibraryService = AlgorithmLibraryService()

  val database = "features_db"
  val sampleTable = FeaturesTableDescription(TableId(database, None, "Sample"),
                                             Nil,
                                             None,
                                             validateSchema = false,
                                             None,
                                             0.67)
  val sampleHeaders = List(
    TableColumn("ID", SqlType.int),
    TableColumn("stress_before_test1", SqlType.numeric),
    TableColumn("score_test1", SqlType.numeric),
    TableColumn("IQ", SqlType.numeric),
    TableColumn("cognitive_task2", SqlType.numeric),
    TableColumn("practice_task2", SqlType.numeric),
    TableColumn("response_time_task2", SqlType.numeric),
    TableColumn("college_math", SqlType.numeric),
    TableColumn("score_math_course1", SqlType.numeric),
    TableColumn("score_math_course2", SqlType.numeric)
  )

  val sampleData = List(
    JsObject("ID"                  -> JsNumber(1),
             "stress_before_test1" -> JsNumber(2.0),
             "score_test1"         -> JsNumber(1.0))
  )

  val cdeTable = FeaturesTableDescription(
    TableId(database, None, "cde_features_a"),
    List(TableColumn("subjectcode", SqlType.varchar)),
    Some(TableColumn("dataset", SqlType.varchar)),
    validateSchema = false,
    None,
    0.67
  )
  val cdeHeaders = List(
    TableColumn("subjectcode", SqlType.varchar),
    TableColumn("apoe4", SqlType.int),
    TableColumn("lefthippocampus", SqlType.numeric),
    TableColumn("dataset", SqlType.varchar)
  )

  val cdeData = List(
    JsObject("subjectcode"     -> JsString("p001"),
             "apoe4"           -> JsNumber(2),
             "lefthippocampus" -> JsNumber(1.37),
             "dataset"         -> JsString("desd-synthdata"))
  )

  val tables: Set[FeaturesTableDescription] = Set(sampleTable, cdeTable)
  val tablesContent: Map[TableId, (Headers, List[JsObject])] = Map(
    sampleTable.table -> (sampleHeaders -> sampleData),
    cdeTable.table    -> (cdeHeaders    -> cdeData)
  )

  lazy val featuresService: FeaturesService[IO] = FeaturesService(
    new FeaturesInMemoryRepository[IO](database, tables, tablesContent)
  )

  val featuresTableId = TableId("features_db", None, "Sample")

  lazy val emptyFeaturesTableService: FeaturesTableService[IO] = new FeaturesTableServiceImpl(
    new FeaturesTableInMemoryRepository[IO](featuresTableId, Nil, None, Nil)
  )

  implicit val ec: ExecutionContext                       = ExecutionContext.global
  implicit lazy val defaultContextShift: ContextShift[IO] = IOContextShift.global
  implicit lazy val defaultTimer: Timer[IO]               = cats.effect.IO.timer(ec)

  def databaseServices(config: WokenConfiguration): DatabaseServices[IO] = {
    val datasetService: DatasetService = ConfBasedDatasetService(config.config)
    val queryToJobService = QueryToJobService(featuresService,
                                              localVariablesMetaService,
                                              config.jobs,
                                              config.algorithmLookup)
    DatabaseServices(
      config,
      featuresService,
      jobResultService,
      localVariablesMetaService,
      queryToJobService,
      datasetService,
      algorithmLibraryService
    )
  }

  lazy val dispatcherService: DispatcherService = new DispatcherService {
    override def localDatasets: Set[DatasetId]                                        = ???
    override def dispatchTo(dataset: DatasetId): Option[RemoteLocation]               = ???
    override def dispatchTo(datasets: Set[DatasetId]): (Set[RemoteLocation], Boolean) = ???
    override def dispatchRemoteMiningFlow
      : Flow[MiningQuery, (RemoteLocation, QueryResult), NotUsed] = ???
    override def dispatchRemoteExperimentFlow
      : Flow[ExperimentQuery, (RemoteLocation, QueryResult), NotUsed] = ???
    override def dispatchVariablesQueryFlow[F[_]: Effect](
        datasetService: DatasetService,
        variablesMetaService: VariablesMetaService[F]
    ): Flow[VariablesForDatasetsQuery,
            (VariablesForDatasetsQuery, VariablesForDatasetsResponse),
            NotUsed] = ???
  }

  def backendServices(system: ActorSystem): BackendServices =
    BackendServices(dispatcherService = dispatcherService,
                    chronosHttp = system.actorOf(FakeActors.echoActorProps))
}
