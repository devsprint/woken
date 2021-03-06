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

package ch.chuv.lren.woken.mining
import akka.actor.ActorRef
import cats.data.Validated.Valid
import cats.effect.IO
import ch.chuv.lren.woken.config.{ DatabaseConfiguration, JobsConfiguration }
import ch.chuv.lren.woken.cromwell.core.ConfigUtil
import ch.chuv.lren.woken.service.TestServices.jobResultService
import ch.chuv.lren.woken.service.{ FeaturesService, FeaturesTableService, TestServices }

object FakeCoordinatorConfig {

  val noDbConfig =
    DatabaseConfiguration(dbiDriver = "DBI",
                          dbApiDriver = "DBAPI",
                          jdbcDriver = "java.lang.String",
                          jdbcUrl = "",
                          host = "",
                          port = 0,
                          database = "db",
                          user = "",
                          password = "",
                          poolSize = 5,
                          tables = Set())
  val noJobsConf =
    JobsConfiguration("testNode",
                      "noone",
                      "http://nowhere",
                      "features",
                      "features",
                      "features",
                      "results",
                      "meta",
                      0.5,
                      512)

  val fakeFeaturesService: FeaturesService[IO]           = TestServices.featuresService
  val fakeFeaturesTableService: FeaturesTableService[IO] = TestServices.emptyFeaturesTableService

  val jdbcConfigs: String => ConfigUtil.Validation[DatabaseConfiguration] = _ => Valid(noDbConfig)

  def coordinatorConfig(chronosService: ActorRef): CoordinatorConfig[IO] =
    CoordinatorConfig(
      chronosService,
      None,
      fakeFeaturesService,
      jobResultService,
      noJobsConf,
      jdbcConfigs.apply
    )

}
