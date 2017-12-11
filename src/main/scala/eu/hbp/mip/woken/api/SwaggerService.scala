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

package eu.hbp.mip.woken.api

import com.github.swagger.akka.SwaggerHttpService

object SwaggerService extends SwaggerHttpService {

//  override def apiTypes   = Seq(typeOf[MiningServiceApi])
//  override def apiVersion = "0.2"
//  override def baseUrl    = "/" // let swagger-ui determine the host and port
//  override def docsPath   = "api-docs"
//  override def apiInfo    = Some(new ApiInfo("Api users", "", "", "", "", ""))
  override def apiClasses: Set[Class[_]] = Set(classOf[MiningServiceApi])
}
