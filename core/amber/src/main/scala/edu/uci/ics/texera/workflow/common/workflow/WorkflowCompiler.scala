package edu.uci.ics.texera.workflow.common.workflow

import com.google.protobuf.timestamp.Timestamp
import com.typesafe.scalalogging.LazyLogging
import edu.uci.ics.amber.engine.architecture.controller.Workflow
import edu.uci.ics.amber.engine.common.virtualidentity.OperatorIdentity
import edu.uci.ics.amber.error.ErrorUtils.getStackTraceWithAllCauses
import edu.uci.ics.texera.Utils.objectMapper
import edu.uci.ics.texera.web.model.websocket.request.LogicalPlanPojo
import edu.uci.ics.texera.web.service.ExecutionsMetadataPersistService
import edu.uci.ics.texera.web.storage.ExecutionStateStore
import edu.uci.ics.texera.web.storage.ExecutionStateStore.updateWorkflowState
import edu.uci.ics.texera.web.workflowruntimestate.FatalErrorType.COMPILATION_ERROR
import edu.uci.ics.texera.web.workflowruntimestate.WorkflowAggregatedState.FAILED
import edu.uci.ics.texera.web.workflowruntimestate.WorkflowFatalError
import edu.uci.ics.texera.workflow.common.WorkflowContext
import edu.uci.ics.texera.workflow.common.storage.OpResultStorage
import edu.uci.ics.texera.workflow.common.tuple.schema.Schema
import edu.uci.ics.texera.workflow.operators.sink.managed.ProgressiveSinkOpDesc
import edu.uci.ics.texera.workflow.operators.visualization.VisualizationConstants

import java.time.Instant
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

case class WorkflowCompilationResult(
                                      physicalPlan: Option[PhysicalPlan], // if physical plan is none, the compilation is failed
                                      operatorIdToInputSchemas: Map[OperatorIdentity, List[Option[Schema]]],
                                      operatorErrors: List[WorkflowFatalError]
)

class WorkflowCompiler(
    context: WorkflowContext
) extends LazyLogging {

  /**
    * Compile a workflow to physical plan, along with the schema propagation result and error(if any)
    *
    * @param logicalPlanPojo the pojo parsed from workflow str provided by user
    * @return WorkflowCompilationResult, containing the physical plan, input schemas per op and error per op
    */
  def compile(
      logicalPlanPojo: LogicalPlanPojo
  ): WorkflowCompilationResult = {
    // first compile the pojo to logical plan
    val errorList = new ArrayBuffer[(OperatorIdentity, Throwable)]()

    var logicalPlan: LogicalPlan = LogicalPlan(logicalPlanPojo)

    // step1: come up with the physical plan and do the schema propagation
    // from logical plan to physical plan
    var physicalPlan: Option[PhysicalPlan] = None
    try {
      physicalPlan = Some(PhysicalPlan(context, logicalPlan))
    } catch {
      case ex: Throwable =>
        physicalPlan = None
        errorList.append((new OperatorIdentity(""), ex))
    }

    if (physicalPlan.isEmpty) {
      return WorkflowCompilationResult(
        None,
        Map.empty,
        errorList.map {
          case (opId, err) =>
            logger.error("Error occurred in expanding logical plan to physical plan", err)
            WorkflowFatalError(
              COMPILATION_ERROR,
              Timestamp(Instant.now),
              err.getMessage,
              getStackTraceWithAllCauses(err),
              opId.id
            )
        }.toList
      )
    }

    // Extract physical input schemas, excluding internal ports
    val physicalInputSchemas = physicalPlan.get.operators.map { physicalOp =>
      physicalOp.id -> physicalOp.inputPorts.values
        .filterNot(_._1.id.internal)
        .map {
          case (port, _, schema) => port.id -> schema.toOption
        }
    }

    // Group the physical input schemas by their logical operator ID and consolidate the schemas
    val opIdToInputSchemas = physicalInputSchemas
      .groupBy(_._1.logicalOpId)
      .view
      .mapValues(_.flatMap(_._2).toList.sortBy(_._1.id).map(_._2))
      .toMap

    // step2: propagate schema to get any static errors
    logicalPlan = SinkInjectionTransformer.transform(
      logicalPlanPojo.opsToViewResult,
      logicalPlan
    )

    logicalPlan.propagateWorkflowSchema(context, Some(errorList))
    if (errorList.nonEmpty) {
      // encounter errors during static error check,
      //   so directly return None as physical plan, schema map and non-empty error map
      physicalPlan = None
    }
    WorkflowCompilationResult(
      physicalPlan,
      opIdToInputSchemas,
      // map each error to WorkflowFatalError, and report them in the log
      errorList.map {
        case (opId, err) =>
          logger.error(s"Error occurred in logical plan compilation for opId: $opId", err)
          WorkflowFatalError(
            COMPILATION_ERROR,
            Timestamp(Instant.now),
            err.getMessage,
            getStackTraceWithAllCauses(err),
            opId.id
          )
      }.toList
    )
  }

  /**
    * After separating the compiler as a standalone service, this function needs to be removed.
    */
  @Deprecated
  def compileLogicalPlan(
      logicalPlanPojo: LogicalPlanPojo,
      executionStateStore: ExecutionStateStore
  ): LogicalPlan = {
    val errorList = new ArrayBuffer[(OperatorIdentity, Throwable)]()
    // remove previous error state
    executionStateStore.metadataStore.updateState { metadataStore =>
      metadataStore.withFatalErrors(
        metadataStore.fatalErrors.filter(e => e.`type` != COMPILATION_ERROR)
      )
    }

    var logicalPlan: LogicalPlan = LogicalPlan(logicalPlanPojo)
    logicalPlan = SinkInjectionTransformer.transform(
      logicalPlanPojo.opsToViewResult,
      logicalPlan
    )

    logicalPlan.propagateWorkflowSchema(context, Some(errorList))

    // report compilation errors
    if (errorList.nonEmpty) {
      val executionErrors = errorList.map {
        case (opId, err) =>
          logger.error("error occurred in logical plan compilation", err)
          WorkflowFatalError(
            COMPILATION_ERROR,
            Timestamp(Instant.now),
            err.toString,
            getStackTraceWithAllCauses(err),
            opId.id
          )
      }
      executionStateStore.metadataStore.updateState(metadataStore =>
        updateWorkflowState(FAILED, metadataStore).addFatalErrors(executionErrors.toSeq: _*)
      )
      throw new RuntimeException("workflow failed to compile")
    }
    logicalPlan
  }

  /**
    * After separating the compiler as a standalone service, this function needs to be removed.
    * The sink storage assignment needs to be pushed to the standalone workflow execution service.
    */
  @Deprecated
  def compile(
      logicalPlanPojo: LogicalPlanPojo,
      opResultStorage: OpResultStorage,
      executionStateStore: ExecutionStateStore
  ): Workflow = {
    // generate a LogicalPlan. The logical plan is the injected with all necessary sinks
    val logicalPlan = compileLogicalPlan(logicalPlanPojo, executionStateStore)

    assignSinkStorage(
      logicalPlan,
      context,
      opResultStorage
    )

    // the PhysicalPlan with topology expanded.
    val physicalPlan = PhysicalPlan(context, logicalPlan)

    Workflow(
      context,
      logicalPlan,
      physicalPlan
    )
  }

  /**
    * Once standalone compiler is done, move this function to the execution service, and change the 1st parameter from LogicalPlan to PhysicalPlan
    */
  @Deprecated
  private def assignSinkStorage(
      logicalPlan: LogicalPlan,
      context: WorkflowContext,
      storage: OpResultStorage,
      reuseStorageSet: Set[OperatorIdentity] = Set()
  ): Unit = {
    // create a JSON object that holds pointers to the workflow's results in Mongo
    val resultsJSON = objectMapper.createObjectNode()
    val sinksPointers = objectMapper.createArrayNode()
    // assign storage to texera-managed sinks before generating exec config
    logicalPlan.operators.foreach {
      case o @ (sink: ProgressiveSinkOpDesc) =>
        val storageKey = sink.getUpstreamId.getOrElse(o.operatorIdentifier)
        // due to the size limit of single document in mongoDB (16MB)
        // for sinks visualizing HTMLs which could possibly be large in size, we always use the memory storage.
        val storageType = {
          if (sink.getChartType.contains(VisualizationConstants.HTML_VIZ)) OpResultStorage.MEMORY
          else OpResultStorage.defaultStorageMode
        }
        if (reuseStorageSet.contains(storageKey) && storage.contains(storageKey)) {
          sink.setStorage(storage.get(storageKey))
        } else {
          sink.setStorage(
            storage.create(
              s"${o.getContext.executionId}_",
              storageKey,
              storageType
            )
          )

          sink.getStorage.setSchema(
            logicalPlan.getOperator(storageKey).outputPortToSchemaMapping.values.head
          )
          // add the sink collection name to the JSON array of sinks
          val storageNode = objectMapper.createObjectNode()
          storageNode.put("storageType", storageType)
          storageNode.put("storageKey", s"${o.getContext.executionId}_$storageKey")
          sinksPointers.add(storageNode)
        }
        storage.get(storageKey)

      case _ =>
    }
    // update execution entry in MySQL to have pointers to the mongo collections
    resultsJSON.set("results", sinksPointers)
    ExecutionsMetadataPersistService.tryUpdateExistingExecution(context.executionId) {
      _.setResult(resultsJSON.toString)
    }
  }

}
