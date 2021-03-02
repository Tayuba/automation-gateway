package at.rocworks.gateway.graphql

import at.rocworks.gateway.core.data.Globals
import at.rocworks.gateway.core.data.Topic
import at.rocworks.gateway.core.data.Value

import graphql.GraphQL
import graphql.schema.DataFetcher
import graphql.schema.DataFetchingEnvironment
import graphql.schema.idl.*

import io.reactivex.*

import io.vertx.core.AbstractVerticle
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.graphql.ApolloWSHandler
import io.vertx.ext.web.handler.graphql.GraphQLHandler

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*
import java.util.concurrent.CompletableFuture
import kotlin.collections.HashMap

import java.time.format.DateTimeFormatter
import java.util.logging.Level
import java.util.logging.Logger

class GraphQLServer(private val defaultSystem: String) : AbstractVerticle() {
    // TODO: remove system and use Topic instead of NodeId? Or add Opc as prefix to the functions
    // TODO: add a search function - browsing with deep search on browsename
    // TODO: Implement scalar "variant"
    // TODO: Subscribe tp multiple nodes

    val defaultType = "OPC"

    companion object {
        fun create(vertx: Vertx, config: JsonObject, defaultSystem: String) {
            //val logger = LoggerFactory.getLogger(this.javaClass.simpleName)

            val graphQL = GraphQLServer(defaultSystem)
            vertx.deployVerticle(graphQL)

            val router = Router.router(vertx)
            router.route().handler(BodyHandler.create())
            router.route("/graphql").handler(ApolloWSHandler.create(graphQL.graphql))
            router.route("/graphql").handler(GraphQLHandler.create(graphQL.graphql))

            val httpServerOptions = HttpServerOptions()
                .setWebSocketSubProtocols(listOf("graphql-ws"))
            val httpServer = vertx.createHttpServer(httpServerOptions)
            val httpPort = config.getInteger("Port", 4000)
            httpServer.requestHandler(router).listen(httpPort)
        }
    }

    private val id = this.javaClass.simpleName
    private val logger = LoggerFactory.getLogger(id)
    val graphql : GraphQL

    init {
        Logger.getLogger(id).level = Level.ALL

        // enum Type must match the Globals.BUS_ROOT_URI_*
        val schema = """
            | enum Type { 
            |   OPC
            |   PLC
            | }  
            | 
            | type Query {
            |   ServerInfo(System: String): ServerInfo
            |   
            |   NodeValue(Type: Type, System: String, NodeId: ID!): Value 
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]): [Value]
            |   BrowseNode(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
            |   FindNodes(Type: Type, System: String, NodeId: ID, Filter: String): [Node]
            | }
            | 
            | type Mutation {
            |   NodeValue(Type: Type, System: String, NodeId: ID!, Value: String!): Boolean
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]!, Values: [String!]!): [Boolean]
            | }
            | 
            | type Subscription {
            |   NodeValue(Type: Type, System: String, NodeId: ID!): Value
            |   NodeValues(Type: Type, System: String, NodeIds: [ID!]!): Value
            | }
            | 
            | type Value {
            |   System: String
            |   NodeId: ID
            |   Value: String
            |   DataType: String
            |   DataTypeId: Int
            |   StatusCode: String
            |   SourceTime: String
            |   ServerTime: String
            |   History(Log: ID, From: String, To: String, LastSeconds: Int): [Value]   
            | }
            | 
            | type Node {
            |   System: String
            |   NodeId: ID
            |   Name: String
            |   DisplayName: String
            |   NodeClass: String
            |   Value: Value
            |   Nodes(Filter: String): [Node]
            |   History(Log: ID, From: String, To: String, LastSeconds: Int): [Value]
            |   SetValue(Value: String): Boolean
            | }
            | 
            | type ServerInfo {
            |   Server: [String]
            |   Namespace: [String]
            |   BuildInfo: String
            |   StartTime: String
            |   CurrentTime: String
            |   ServerStatus: String
            | }
            """.trimMargin()
        val schemaParser = SchemaParser()
        val typeDefinitionRegistry = schemaParser.parse(schema)
        val runtimeWiring =
            RuntimeWiring.newRuntimeWiring()
                .type(
                    TypeRuntimeWiring.newTypeWiring("Query")
                        .dataFetcher("ServerInfo", getServerInfo())
                        .dataFetcher("NodeValue", getNodeValue())
                        .dataFetcher("NodeValues", getNodeValues())
                        .dataFetcher("BrowseNode", getBrowseNode())
                        .dataFetcher("FindNodes", getFindNodes())
                )
                .type(
                    TypeRuntimeWiring.newTypeWiring("Mutation")
                        .dataFetcher("NodeValue", setNodeValue())
                        .dataFetcher("NodeValues", setNodeValues())
                )
                .type(
                    TypeRuntimeWiring.newTypeWiring("Subscription")
                        .dataFetcher("NodeValue", this::subNodeValue)
                        .dataFetcher("NodeValues", this::subNodeValues)
                )
                .type(
                    TypeRuntimeWiring.newTypeWiring("Node")
                        .dataFetcher("Value", getNodeValue())
                        .dataFetcher("Nodes", getBrowseNode())
                        .dataFetcher("History", getValueHistory())
                        .dataFetcher("SetValue", setNodeValue())
                )
                .type(
                    TypeRuntimeWiring.newTypeWiring("Value")
                        .dataFetcher("History", getValueHistory())
                )
                .build()
        val schemaGenerator = SchemaGenerator()
        val graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring)
        graphql = GraphQL.newGraphQL(graphQLSchema).build()
    }

    private fun getServerInfo(): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> { env ->
            val promise = CompletableFuture<Map<String, Any?>>()
            val system = env?.getArgument("System") ?: defaultSystem
            try {
                vertx.eventBus().request<JsonObject>("${Globals.BUS_ROOT_URI_OPC}/$system/ServerInfo", JsonObject()) {
                    logger.debug("getServerInfo read response [{}] [{}]", it.succeeded(), it.result()?.body())
                    if (it.succeeded()) {
                        val result = it.result().body().getJsonObject("Result")
                        val map = HashMap<String, Any>()
                        map["Server"] = result.getJsonArray("Server").toList()
                        map["Namespace"] = result.getJsonArray("Namespace").toList()
                        map["BuildInfo"] = result.getString("BuildInfo")
                        map["StartTime"] = result.getString("StartTime")
                        map["CurrentTime"] = result.getString("CurrentTime")
                        map["ServerStatus"] = result.getString("ServerStatus")
                        promise.complete(map)
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }
            promise
        }
    }

    private fun getEnvArgument(
        env: DataFetchingEnvironment,
        name: String
    ): String? {
        val ctx: Map<String, Any>? = env.getSource()
        return env.getArgumentOrDefault(name, ctx?.get(name) as String?)
    }

    private fun getEnvTypeAndSystem(env: DataFetchingEnvironment): Pair<String, String> {
        val ctx: Map<String, Any>? = env.getSource()
        val type: String = env.getArgument("Type")
            ?: ctx?.get("Type") as String?
            ?: defaultType

        val system: String = env.getArgument("System")
            ?: ctx?.get("System") as String?
            ?: defaultSystem

        return Pair(type, system)
    }

    private fun getNodeValue(): DataFetcher<CompletableFuture<Map<String, Any?>>> {
        return DataFetcher<CompletableFuture<Map<String, Any?>>> { env ->
            val promise = CompletableFuture<Map<String, Any?>>()
            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env,"NodeId") ?: ""

            val request = JsonObject()
            request.put("NodeId", nodeId)

            try {
                logger.debug("getNodeValue read request...")
                vertx.eventBus().request<JsonObject>("${type.toLowerCase()}/$system/Read", request) {
                    logger.debug("getNodeValue read response [{}] [{}]", it.succeeded(), it.result()?.body())
                    if (it.succeeded()) {
                        try {
                            val data = it.result().body().getJsonObject("Result")
                            if (data!=null) {
                                val input = Value.decodeFromJson(data)
                                val result = valueToGraphQL(system, nodeId, input)
                                promise.complete(result)
                            } else {
                                logger.warn("No result in read response!")
                                promise.complete(null)
                            }
                        } catch (e: Exception) {
                            logger.error(e.message)
                            promise.complete(null)
                        }
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }

    private fun getNodeValues(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()

            val (type, system) = getEnvTypeAndSystem(env)

            val nodeIds = env?.getArgument("NodeIds") ?: listOf<String>()

            val request = JsonObject()
            val nodes = JsonArray()
            nodeIds.forEach { nodes.add(it) }
            request.put("NodeId", nodes)

            try {
                vertx.eventBus().request<JsonObject>("${type}/$system/Read", request) { response ->
                    logger.debug("getNodeValues read response [{}] [{}]", response.succeeded(), response.result()?.body())
                    if (response.succeeded()) {
                        val list = response.result().body().getJsonArray("Result")
                        val result = nodeIds.zip(list.filterIsInstance<JsonObject>()).map {
                            valueToGraphQL(system, it.first, Value.decodeFromJson(it.second))
                        }
                        promise.complete(result)
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }

    private fun setNodeValue(): DataFetcher<CompletableFuture<Boolean>> {
        return DataFetcher<CompletableFuture<Boolean>> { env ->
            val promise = CompletableFuture<Boolean>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId: String = getEnvArgument(env, "NodeId") ?: ""
            val value: String = getEnvArgument(env, "Value") ?: ""

            val request = JsonObject()
            request.put("NodeId", nodeId)
            request.put("Value", value)

            try {
                vertx.eventBus().request<JsonObject>("${type}/$system/Write", request) {
                    logger.debug("setNodeValue write response [{}] [{}]", it.succeeded(), it.result()?.body())
                    promise.complete(
                        if (it.succeeded()) {
                            it.result().body().getBoolean("Ok")
                        } else {
                            false
                        })
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }



    private fun setNodeValues(): DataFetcher<CompletableFuture<List<Boolean>>> {
        return DataFetcher<CompletableFuture<List<Boolean>>> { env ->
            val promise = CompletableFuture<List<Boolean>>()
            val (type, system) = getEnvTypeAndSystem(env)
            val nodeIds = env.getArgument("NodeIds") as List<String>
            val values = env.getArgument("Values") as List<String>

            val request = JsonObject()
            request.put("NodeId", nodeIds)
            request.put("Value", values)

            try {
                vertx.eventBus().request<JsonObject>("${type}/$system/Write", request) {
                    logger.debug("setNodeValue write response [{}] [{}]", it.succeeded(), it.result()?.body())
                    promise.complete(
                        if (it.succeeded()) {
                            it.result()
                                .body()
                                .getJsonArray("Ok")
                                .map { ok -> ok as? Boolean ?: false }
                        } else {
                            nodeIds.map { false }
                        })
                }
            } catch (e: Exception){
                e.printStackTrace()
            }

            promise
        }
    }

    private fun getBrowseNode(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()

            val (type, system) = getEnvTypeAndSystem(env)
            val nodeId = getEnvArgument(env, "NodeId") ?: "i=85"
            val filter : String? = getEnvArgument(env,"Filter")

            val request = JsonObject()
            request.put("NodeId", nodeId)

            try {
                vertx.eventBus()
                    .request<JsonObject>("${type}/$system/Browse", request) { message ->
                    logger.debug("getNodes browse response [{}] [{}]", message.succeeded(), message.result()?.body())
                    if (message.succeeded()) {
                        try {
                            val list = message.result().body().getJsonArray("Result")
                            val result = list
                                .filterIsInstance<JsonObject>()
                                .filter { filter == null || filter.toRegex().matches(it.getString("BrowseName"))}
                                .map { input ->
                                val item = HashMap<String, Any>()
                                item["System"] = system
                                item["NodeId"] = input.getString("NodeId")
                                item["Name"] = input.getString("BrowseName")
                                item["DisplayName"] = input.getString("DisplayName")
                                item["NodeClass"] = input.getString("NodeClass")
                                item
                            }
                            promise.complete(result)
                        } catch (e: Exception) {
                            promise.completeExceptionally(e)
                        }
                    } else {
                        promise.complete(null)
                    }
                }
            } catch (e: Exception){
                e.printStackTrace()
            }
            promise
        }
    }

    private fun getFindNodes(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->

            val (system, type) = getEnvTypeAndSystem(env)
            val nodeId : String = getEnvArgument(env,"NodeId") ?: "i=85"
            val filter : String? = getEnvArgument(env,"Filter")

            val overallResult =  mutableListOf<HashMap<String, Any>>()

            fun find(nodeId: String): CompletableFuture<Boolean> {
                val promise = CompletableFuture<Boolean>()
                val request = JsonObject()
                request.put("NodeId", nodeId)
                vertx.eventBus()
                    .request<JsonObject>("${type}/$system/Browse", request) { message ->
                        logger.debug("getNodes browse response [{}] [{}]", message.succeeded(), message.result()?.body())
                        if (message.succeeded()) {
                            val result = message.result().body().getJsonArray("Result")?.filterIsInstance<JsonObject>()
                            logger.debug("FindNodes result [{}]", result?.size)
                            if (result!=null) {
                                overallResult.addAll(result
                                    .filter { filter == null || filter.toRegex().matches(it.getString("BrowseName"))}
                                    .map { input ->
                                        val item = HashMap<String, Any>()
                                        item["System"] = system
                                        item["NodeId"] = input.getString("NodeId")
                                        item["Name"] = input.getString("BrowseName")
                                        item["DisplayName"] = input.getString("DisplayName")
                                        item["NodeClass"] = input.getString("NodeClass")
                                        item
                                    }
                                )
                                val next = result
                                    .filter { it.getString("NodeClass") == "Object" }
                                    .map { find(it.getString("NodeId")) }

                                if (next.isNotEmpty()) {
                                    CompletableFuture.allOf(*next.toTypedArray()).thenAccept { promise.complete(true) }
                                } else {
                                    promise.complete(true)
                                }
                            } else promise.complete(false)
                        } else promise.complete(false)
                    }
                return promise
            }

            val promise = CompletableFuture<List<Map<String, Any?>>>()
            find(nodeId).thenAccept { promise.complete(overallResult) }
            promise
        }
    }

    private fun subNodeValue(env: DataFetchingEnvironment): Flowable<Map<String, Any?>> {
        val uuid = UUID.randomUUID().toString()

        val (system, type) = getEnvTypeAndSystem(env)
        val nodeId : String = env.getArgument("NodeId") ?: ""

        val topic = Topic.parseTopic("${type}/$system/node:json/$nodeId")
        val flowable = Flowable.create(FlowableOnSubscribe<Map<String, Any?>> { emitter ->
            val consumer = vertx.eventBus().consumer<Buffer>(topic.topicName) { message ->
                try {
                    val data = message.body().toJsonObject()
                    val output = Value.decodeFromJson(data.getJsonObject("Value"))
                    if (!emitter.isCancelled) emitter.onNext(valueToGraphQL(system, nodeId, output))
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            emitter.setCancellable {
                logger.info("Unsubscribe [{}] [{}]", consumer.address(), uuid)
                consumer.unregister()
                val request = JsonObject().put("ClientId", uuid).put("Topics", listOf(topic.encodeToJson()))
                vertx.eventBus().request<JsonObject>("${topic.systemType}/${topic.systemName}/Unsubscribe", request) {
                    logger.info("Unsubscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
                }
            }
        }, BackpressureStrategy.BUFFER)

        val request = JsonObject().put("ClientId", uuid).put("Topic", topic.encodeToJson())
        vertx.eventBus().request<JsonObject>("${topic.systemType}/${topic.systemName}/Subscribe", request) {
            if (it.succeeded()) {
                logger.info("Subscribe response [{}] [{}] [{}]", topic.topicName, it.result().body().getBoolean("Ok"), uuid)
            } else {
                logger.info("Subscribe not succeeded!")
            }
        }

        return flowable
    }

    private fun subNodeValues(env: DataFetchingEnvironment): Flowable<Map<String, Any?>> {
        val uuid = UUID.randomUUID().toString()

        val (system, type) = getEnvTypeAndSystem(env)
        val nodeIds = env.getArgument("NodeIds") ?: listOf<String>()

        val flowable = Flowable.create(FlowableOnSubscribe<Map<String, Any?>> { emitter ->
            val consumers = nodeIds.map { nodeId ->
                val topic = "${type}/$system/node:json/$nodeId"
                vertx.eventBus().consumer<Buffer>(topic) { message ->
                    try {
                        val data = message.body().toJsonObject()
                        val output = Value.decodeFromJson(data.getJsonObject("Value"))
                        if (!emitter.isCancelled) emitter.onNext(valueToGraphQL(system, nodeId, output))
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            emitter.setCancellable {
                consumers.forEach { consumer ->
                    logger.info("Unsubscribe [{}] [{}]", consumer.address(), uuid)
                    consumer.unregister()
                    val topic = Topic.parseTopic(consumer.address())
                    val request = JsonObject().put("ClientId", uuid).put("Topics", listOf(topic.encodeToJson()))
                    vertx.eventBus().request<JsonObject>("${topic.systemType}/${system}/Unsubscribe", request) {
                        logger.info("Unsubscribe response [{}] [{}]", it.succeeded(), it.result()?.body())
                    }
                }
            }
        }, BackpressureStrategy.BUFFER)

        nodeIds.forEach { nodeId ->
            val topic = Topic.parseTopic("${Globals.BUS_ROOT_URI_OPC}/$system/node:json/$nodeId")
            val request = JsonObject().put("ClientId", uuid).put("Topic", topic.encodeToJson())
            vertx.eventBus().request<JsonObject>("${topic.systemType}/${topic.systemName}/Subscribe", request) {
                if (it.succeeded()) {
                    logger.info("Subscribe response [{}] [{}] [{}]", topic.topicName, it.result().body().getBoolean("Ok"), uuid)
                } else {
                    logger.info("Subscribe not succeeded!")
                }
            }
        }
        return flowable
    }

    private val timeFormatterISO = DateTimeFormatter.ISO_DATE_TIME


    private fun getValueHistory(): DataFetcher<CompletableFuture<List<Map<String, Any?>>>> {
        return DataFetcher<CompletableFuture<List<Map<String, Any?>>>> { env ->
            val promise = CompletableFuture<List<Map<String, Any?>>>()
            if (env==null) {
                promise.complete(listOf())
            } else {
                val log: String = getEnvArgument(env,"Log") ?: "default"
                val (system, type) = getEnvTypeAndSystem(env)
                val nodeId: String = getEnvArgument(env, "NodeId") ?: ""

                var t1 = Instant.now()
                var t2 = Instant.now()

                val t1arg = env.getArgument<String>("From")
                if (t1arg!=null)
                    t1 = Instant.from(timeFormatterISO.parse(t1arg))

                val t2arg = env.getArgument<String>("To")
                if (t2arg!=null)
                    t2 = Instant.from(timeFormatterISO.parse(t2arg))

                val lastSeconds: Int? = env.getArgument("LastSeconds")
                if (lastSeconds!=null) {
                    t1 = (Instant.now()).minusSeconds(lastSeconds.toLong())
                }

                val request = JsonObject()
                request.put("System", system)
                request.put("NodeId", nodeId)
                request.put("T1", t1.toEpochMilli())
                request.put("T2", t2.toEpochMilli())

                vertx.eventBus()
                    .request<JsonObject>("${type}/${log}/QueryHistory", request) { message ->
                        val list =  message.result()?.body()?.getJsonArray("Result") ?: JsonArray()
                        logger.info("Query response [{}] size [{}]", message.succeeded(), list.size())
                        val result = list.filterIsInstance<JsonArray>().map {
                            val item = HashMap<String, Any?>()
                            item["NodeId"] = nodeId
                            item["SourceTime"] = it.getValue(0)
                            item["Value"] = it.getValue(1)
                            item["StatusCode"] = it.getValue(2)
                            item["System"] = it.getValue(3)
                            item
                        }
                        promise.complete(result)
                    }
            }
            promise
        }
    }

    private fun valueToGraphQL(system: String, nodeId: String, input: Value): HashMap<String, Any?> {
        val item = HashMap<String, Any?>()
        item["System"] = system
        item["NodeId"] = nodeId
        item["Value"] = input.value?.toString()
        item["DataType"] = input.dataTypeName
        item["DataTypeId"] = input.dataTypeId
        item["StatusCode"] = input.statusCode.toString()
        item["SourceTime"] = input.sourceTimeAsISO()
        item["ServerTime"] = input.serverTimeAsISO()
        return item
    }

}