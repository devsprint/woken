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

package eu.hbp.mip.woken.core

import java.time.OffsetDateTime

import akka.actor.{ Actor, PoisonPill }
import eu.hbp.mip.woken.core.model.PfaExperimentJobResult
import spray.json._
import ExperimentActor._
import eu.hbp.mip.woken.core.commands.JobCommands

class FakeExperimentActor() extends Actor {

  override def receive: PartialFunction[Any, Unit] = {
    case JobCommands.StartExperimentJob(job) =>
      val pfaModels =
        """
         [
           {
             "input": [],
             "output": [],
             "action": [],
             "cells": []
           }
         ]

        """.stripMargin.parseJson.asInstanceOf[JsArray]

      sender() ! Response(
        job,
        Right(PfaExperimentJobResult(job.jobId, "testNode", OffsetDateTime.now(), pfaModels))
      )
      self ! PoisonPill
  }
}