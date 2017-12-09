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

package eu.hbp.mip.woken.config

import com.typesafe.config.{ Config, ConfigFactory }
import eu.hbp.mip.woken.cromwell.util.ConfigUtil._

object WokenConfig {
  private val config = ConfigFactory.load()

  object app {
    val appConf: Config = config.getConfig("app")

    val systemName: String                  = appConf.getString("systemName")
    val dockerBridgeNetwork: Option[String] = appConf.getStringOption("dockerBridgeNetwork")
    val interface: String                   = appConf.getString("interface")
    val port: Int                           = appConf.getInt("port")
    val jobServiceName: String              = appConf.getString("jobServiceName")
    val basicAuthUsername: String           = appConf.getString("basicAuth.username")
    val basicAuthPassword: String           = appConf.getString("basicAuth.password")
    val sslJksFilePath: String              = appConf.getString("ssl.jks.file.path")
    val sslJksPassword: String              = appConf.getString("ssl.jks.password")

    case class MasterRouterConfig(miningActorsLimit: Int, experimentActorsLimit: Int)

    def masterRouterConfig: MasterRouterConfig = {
      val conf: Config = appConf.getConfig("master.router.actors")
      MasterRouterConfig(
        miningActorsLimit = conf.getInt("mining.limit"),
        experimentActorsLimit = conf.getInt("experiment.limit")
      )
    }

  }

  case class JobServerConf(jobsUrl: String)

  @deprecated
  object jobs {
    val jobsConf: Config = config.getConfig("jobs")

    val node: String                 = jobsConf.getString("node")
    val owner: String                = jobsConf.getString("owner")
    val chronosServerUrl: String     = jobsConf.getString("chronosServerUrl")
    val ldsmDb: Option[String]       = jobsConf.getStringOption("ldsmDb")
    val federationDb: Option[String] = jobsConf.getStringOption("federationDb")
    val resultDb: String             = jobsConf.getString("resultDb")
    val nodesConf: Option[Config]    = jobsConf.getConfigOption("nodes")

    import scala.collection.JavaConversions._

    def nodes: Set[String] =
      nodesConf.fold(Set[String]())(
        c => c.entrySet().map(_.getKey.takeWhile(_ != '.'))(collection.breakOut)
      )

    def nodeConfig(node: String): JobServerConf =
      JobServerConf(nodesConf.get.getConfig(node).getString("jobsUrl"))
  }

  object defaultSettings {
    lazy val defaultSettingsConf: Config = config.getConfig("defaultSettings")
    lazy val requestConfig: Config       = defaultSettingsConf.getConfig("request")
    lazy val mainTable: String           = requestConfig.getString("mainTable")

    def dockerImage(plot: String): String =
      requestConfig.getConfig("functions").getConfig(plot).getString("image")

    def isPredictive(plot: String): Boolean =
      requestConfig.getConfig("functions").getConfig(plot).getBoolean("predictive")

    lazy val defaultDb: String     = requestConfig.getString("inDb")
    lazy val defaultMetaDb: String = requestConfig.getString("metaDb")
  }

}
