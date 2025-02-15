/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution

import io.glutenproject.GlutenConfig
import io.glutenproject.events.GlutenPlanFallbackEvent
import io.glutenproject.extension.GlutenPlan
import io.glutenproject.extension.columnar.{TRANSFORM_UNSUPPORTED, TransformHints}
import io.glutenproject.utils.LogLevelUtil
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.catalyst.trees.TreeNodeTag
import org.apache.spark.sql.catalyst.util.StringUtils.PlanStringConcat
import org.apache.spark.sql.execution.GlutenFallbackReporter.FALLBACK_REASON_TAG
import org.apache.spark.sql.execution.ui.GlutenEventUtils

/**
 * This rule is used to collect all fallback reason.
 * 1. print fallback reason for each plan node
 * 2. post all fallback reason using one event
 */
case class GlutenFallbackReporter(
    glutenConfig: GlutenConfig,
    spark: SparkSession) extends Rule[SparkPlan] with LogLevelUtil {

  override def apply(plan: SparkPlan): SparkPlan = {
    if (!glutenConfig.enableFallbackReport) {
      return plan
    }
    printFallbackReason(plan)
    postFallbackReason(plan)
    plan
  }

  private def logFallbackReason(logLevel: String, nodeName: String, reason: String): Unit = {
    logOnLevel(logLevel, s"Validation failed for plan: $nodeName, due to: $reason.")
  }

  private def printFallbackReason(plan: SparkPlan): Unit = {
    val validateFailureLogLevel = glutenConfig.validateFailureLogLevel
    plan.foreach {
      case _: GlutenPlan => // ignore
      case plan: SparkPlan =>
        plan.logicalLink.flatMap(_.getTagValue(FALLBACK_REASON_TAG)) match {
          case Some(_) => // We have logged it before, so skip it
          case _ =>
            // some SparkPlan do not have hint, e.g., `ColumnarAQEShuffleRead`
            TransformHints.getHintOption(plan) match {
              case Some(TRANSFORM_UNSUPPORTED(reason)) =>
                logFallbackReason(validateFailureLogLevel, plan.nodeName, reason.getOrElse(""))
                // With in next round stage in AQE, the physical plan would be a new instance that
                // can not preserve the tag, so we need to set the fallback reason to logical plan.
                // Then we can be aware of the fallback reason for the whole plan.
                plan.logicalLink.foreach { logicalPlan =>
                  logicalPlan.setTagValue(FALLBACK_REASON_TAG, reason.getOrElse(""))
                }
              case _ =>
            }
        }
    }
  }

  private def postFallbackReason(plan: SparkPlan): Unit = {
    val sc = spark.sparkContext
    val executionId = sc.getLocalProperty(SQLExecution.EXECUTION_ID_KEY)
    if (executionId == null) {
      logDebug(s"Unknown execution id for plan: $plan")
      return
    }
    val concat = new PlanStringConcat()
    concat.append("== Physical Plan ==\n")
    val (numGlutenNodes, fallbackNodeToReason) = GlutenExplainUtils.processPlan(
      plan, concat.append)

    val event = GlutenPlanFallbackEvent(
      executionId.toLong,
      numGlutenNodes,
      fallbackNodeToReason.size,
      concat.toString(),
      fallbackNodeToReason
    )
    GlutenEventUtils.post(sc, event)
  }
}

object GlutenFallbackReporter {
  // A tag used to inject to logical plan to preserve the fallback reason
  val FALLBACK_REASON_TAG = new TreeNodeTag[String]("GLUTEN_FALLBACK_REASON")
}
