/**
 * Copyright (c) 2002-2013 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher

import internal.commands._
import internal.executionplan.ExecutionPlanBuilder
import internal.LRUCache
import internal.spi.gdsimpl.TransactionBoundQueryContext
import scala.collection.JavaConverters._
import java.util.{Map => JavaMap}
import org.neo4j.kernel.{GraphDatabaseAPI, InternalAbstractGraphDatabase}
import org.neo4j.graphdb.GraphDatabaseService
import org.neo4j.graphdb.factory.GraphDatabaseSettings
import org.neo4j.kernel.impl.util.StringLogger

class ExecutionEngine(graph: GraphDatabaseService, logger: StringLogger = StringLogger.DEV_NULL) {
  checkScalaVersion()

  require(graph != null, "Can't work with a null graph database")

  val parser = createCorrectParser()
  val planBuilder = new ExecutionPlanBuilder(graph)

  private def createCorrectParser() = if (graph.isInstanceOf[InternalAbstractGraphDatabase]) {
    val database = graph.asInstanceOf[InternalAbstractGraphDatabase]
    database.getConfig.get(GraphDatabaseSettings.cypher_parser_version) match {
      case v:String => new CypherParser(v)
      case _ => new CypherParser()
    }
  }
  else {
    new CypherParser()
  }

  @throws(classOf[SyntaxException])
  def execute(query: String): ExecutionResult = execute(query, Map[String, Any]())

  @throws(classOf[SyntaxException])
  def execute(query: String, params: Map[String, Any]): ExecutionResult = {
    logger.info(query)

    val plan = prepare(query)
    val ctx = new TransactionBoundQueryContext(graph.asInstanceOf[GraphDatabaseAPI])
    plan.execute(ctx, params)
  }

  @throws(classOf[SyntaxException])
  def execute(query: String, params: JavaMap[String, Any]): ExecutionResult = execute(query, params.asScala.toMap)

  @throws(classOf[SyntaxException])
  def prepare(query: String): ExecutionPlan =
    executionPlanCache.getOrElseUpdate(query, planBuilder.build(parser.parse(query)))

  def isPrepared(query : String) : Boolean =
    executionPlanCache.containsKey(query)

  @throws(classOf[SyntaxException])
  @deprecated(message = "You should not parse queries manually any more. Use the execute(String) instead")
  def execute(query: AbstractQuery): ExecutionResult = execute(query, Map[String, Any]())

  @throws(classOf[SyntaxException])
  @deprecated(message = "You should not parse queries manually any more. Use the execute(String) instead")
  def execute(query: AbstractQuery, map: JavaMap[String, Any]): ExecutionResult = execute(query, map.asScala.toMap)

  @throws(classOf[SyntaxException])
  @deprecated(message = "You should not parse queries manually any more. Use the execute(String) instead")
  def execute(query: AbstractQuery, params: Map[String, Any]): ExecutionResult = {
    val ctx = new TransactionBoundQueryContext(graph.asInstanceOf[GraphDatabaseAPI])
    planBuilder.build(query).execute(ctx, params)
  }

  private def checkScalaVersion() {
    if (util.Properties.versionString.matches("^version 2.9.0")) {
      throw new Error("Cypher can only run with Scala 2.9.0. It looks like the Scala version is: " +
        util.Properties.versionString)
    }
  }

  private val executionPlanCache = new LRUCache[String, ExecutionPlan]( getQueryCacheSize() ) {}

  private def getQueryCacheSize() : Int = if (graph.isInstanceOf[InternalAbstractGraphDatabase]) {
    val database = graph.asInstanceOf[InternalAbstractGraphDatabase]
    database.getConfig.get(GraphDatabaseSettings.query_cache_size) match {
      case v:java.lang.Integer => v
      case _ => 100
    }
  } else {
    100
  }
}

