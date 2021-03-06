/**************************************************************************************
 * Copyright (C) 2006-2015 EsperTech Inc. All rights reserved.                        *
 * http://www.espertech.com/esper                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.core;

import com.espertech.esper.client.ConfigurationInformation;
import com.espertech.esper.client.EPException;
import com.espertech.esper.client.EventBean;
import com.espertech.esper.client.EventType;
import com.espertech.esper.collection.IterablesArrayIterator;
import com.espertech.esper.collection.Pair;
import com.espertech.esper.core.service.StatementContext;
import com.espertech.esper.epl.db.DataCache;
import com.espertech.esper.epl.db.PollExecStrategy;
import com.espertech.esper.epl.expression.core.*;
import com.espertech.esper.epl.expression.visitor.ExprNodeIdentifierVisitor;
import com.espertech.esper.epl.join.pollindex.PollResultIndexingStrategy;
import com.espertech.esper.epl.join.table.EventTable;
import com.espertech.esper.epl.join.table.UnindexedEventTableList;
import com.espertech.esper.epl.spec.MethodStreamSpec;
import com.espertech.esper.epl.table.mgmt.TableService;
import com.espertech.esper.epl.variable.VariableReader;
import com.espertech.esper.epl.variable.VariableService;
import com.espertech.esper.event.EventAdapterService;
import com.espertech.esper.schedule.SchedulingService;
import com.espertech.esper.schedule.TimeProvider;
import com.espertech.esper.util.JavaClassHelper;
import com.espertech.esper.view.HistoricalEventViewable;
import com.espertech.esper.view.View;
import com.espertech.esper.view.ViewSupport;
import net.sf.cglib.reflect.FastMethod;

import java.util.*;

/**
 * Polling-data provider that calls a static method on a class and passed parameters, and wraps the
 * results as POJO events.
 */
public class MethodPollingViewable implements HistoricalEventViewable
{
    private final boolean isStaticMethod;
    private final Class methodProviderClass;
    private final MethodStreamSpec methodStreamSpec;
    private final List<ExprNode> inputParameters;
    private final DataCache dataCache;
    private final EventType eventType;
    private final ThreadLocal<DataCache> dataCacheThreadLocal = new ThreadLocal<DataCache>();
    private final ExprEvaluatorContext exprEvaluatorContext;
    private final MethodPollingViewableMeta metadata;

    private PollExecStrategy pollExecStrategy;
    private SortedSet<Integer> requiredStreams;
    private ExprEvaluator[] validatedExprNodes;
    private StatementContext statementContext;

    private static final EventBean[][] NULL_ROWS;
    static {
        NULL_ROWS = new EventBean[1][];
        NULL_ROWS[0] = new EventBean[1];
    }
    private static final PollResultIndexingStrategy iteratorIndexingStrategy = new PollResultIndexingStrategy()
    {
        public EventTable[] index(List<EventBean> pollResult, boolean isActiveCache, StatementContext statementContext)
        {
            return new EventTable[] {new UnindexedEventTableList(pollResult, -1)};
        }

        public String toQueryPlan() {
            return this.getClass().getSimpleName() + " unindexed";
        }
    };

    public MethodPollingViewable(
                            boolean isStaticMethod,
                            Class methodProviderClass,
                            MethodStreamSpec methodStreamSpec,
                           List<ExprNode> inputParameters,
                           DataCache dataCache,
                           EventType eventType,
                           ExprEvaluatorContext exprEvaluatorContext,
                            MethodPollingViewableMeta metadata)
    {
        this.isStaticMethod = isStaticMethod;
        this.methodProviderClass = methodProviderClass;
        this.methodStreamSpec = methodStreamSpec;
        this.inputParameters = inputParameters;
        this.dataCache = dataCache;
        this.eventType = eventType;
        this.exprEvaluatorContext = exprEvaluatorContext;
        this.metadata = metadata;
    }

    public void stop()
    {
        pollExecStrategy.destroy();
        dataCache.destroy();
    }

    public ThreadLocal<DataCache> getDataCacheThreadLocal()
    {
        return dataCacheThreadLocal;
    }

    public DataCache getOptionalDataCache() {
        return dataCache;
    }

    public void validate(EngineImportService engineImportService, StreamTypeService streamTypeService, TimeProvider timeProvider,
                         VariableService variableService, TableService tableService, ExprEvaluatorContext exprEvaluatorContext, ConfigurationInformation configSnapshot,
                         SchedulingService schedulingService, String engineURI, Map<Integer, List<ExprNode>> sqlParameters, EventAdapterService eventAdapterService, StatementContext statementContext) throws ExprValidationException {

        this.statementContext = statementContext;

        // validate and visit
        ExprValidationContext validationContext = new ExprValidationContext(streamTypeService, engineImportService, statementContext.getStatementExtensionServicesContext(), null, timeProvider, variableService, tableService, exprEvaluatorContext, eventAdapterService, statementContext.getStatementName(), statementContext.getStatementId(), statementContext.getAnnotations(), null, false, false, true, false, null, false);
        ExprNodeIdentifierVisitor visitor = new ExprNodeIdentifierVisitor(true);
        final List<ExprNode> validatedInputParameters = new ArrayList<ExprNode>();
        for (ExprNode exprNode : inputParameters) {
            ExprNode validated = ExprNodeUtility.getValidatedSubtree(ExprNodeOrigin.METHODINVJOIN, exprNode, validationContext);
            validatedInputParameters.add(validated);
            validated.accept(visitor);
        }

        // determine required streams
        requiredStreams = new TreeSet<Integer>();
        for (Pair<Integer, String> identifier : visitor.getExprProperties()) {
            requiredStreams.add(identifier.getFirst());
        }

        // resolve actual method to use
        ExprNodeUtilResolveExceptionHandler handler = new ExprNodeUtilResolveExceptionHandler() {
            public ExprValidationException handle(Exception e) {
                if (inputParameters.size() == 0)
                {
                    return new ExprValidationException("Method footprint does not match the number or type of expression parameters, expecting no parameters in method: " + e.getMessage());
                }
                Class[] resultTypes = ExprNodeUtility.getExprResultTypes(validatedInputParameters);
                return new ExprValidationException("Method footprint does not match the number or type of expression parameters, expecting a method where parameters are typed '" +
                        JavaClassHelper.getParameterAsString(resultTypes) + "': " + e.getMessage());
            }
        };
        ExprNodeUtilMethodDesc desc = ExprNodeUtility.resolveMethodAllowWildcardAndStream(
                methodProviderClass.getName(), isStaticMethod ? null : methodProviderClass,
                methodStreamSpec.getMethodName(), validatedInputParameters, engineImportService, eventAdapterService, statementContext.getStatementId(),
                false, null, handler, methodStreamSpec.getMethodName(), tableService);
        validatedExprNodes = desc.getChildEvals();

        // Construct polling strategy as a method invocation
        Object invocationTarget = metadata.getInvocationTarget();
        MethodPollingExecStrategyEnum strategy = metadata.getStrategy();
        VariableReader variableReader = metadata.getVariableReader();
        String variableName = metadata.getVariableName();
        FastMethod methodFastClass = desc.getFastMethod();
        if (metadata.getOptionalMapType()!= null) {
            if (desc.getFastMethod().getReturnType().isArray()) {
                pollExecStrategy = new MethodPollingExecStrategyMapArray(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else if (metadata.isCollection()) {
                pollExecStrategy = new MethodPollingExecStrategyMapCollection(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else if (metadata.isIterator()) {
                pollExecStrategy = new MethodPollingExecStrategyMapIterator(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else {
                pollExecStrategy = new MethodPollingExecStrategyMapPlain(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
        }
        else if (metadata.getOptionalOaType() != null) {
            if (desc.getFastMethod().getReturnType() == Object[][].class) {
                pollExecStrategy = new MethodPollingExecStrategyOAArray(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else if (metadata.isCollection()) {
                pollExecStrategy = new MethodPollingExecStrategyOACollection(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else if (metadata.isIterator()) {
                pollExecStrategy = new MethodPollingExecStrategyOAIterator(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else {
                pollExecStrategy = new MethodPollingExecStrategyOAPlain(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
        }
        else {
            if (desc.getFastMethod().getReturnType().isArray()) {
                pollExecStrategy = new MethodPollingExecStrategyPOJOArray(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else if (metadata.isCollection()) {
                pollExecStrategy = new MethodPollingExecStrategyPOJOCollection(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else if (metadata.isIterator()) {
                pollExecStrategy = new MethodPollingExecStrategyPOJOIterator(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
            else {
                pollExecStrategy = new MethodPollingExecStrategyPOJOPlain(eventAdapterService, methodFastClass, eventType, invocationTarget, strategy, variableReader, variableName, variableService);
            }
        }
    }

    public EventTable[][] poll(EventBean[][] lookupEventsPerStream, PollResultIndexingStrategy indexingStrategy, ExprEvaluatorContext exprEvaluatorContext)
    {
        DataCache localDataCache = dataCacheThreadLocal.get();
        boolean strategyStarted = false;

        EventTable[][] resultPerInputRow = new EventTable[lookupEventsPerStream.length][];

        // Get input parameters for each row
        for (int row = 0; row < lookupEventsPerStream.length; row++)
        {
            Object[] lookupValues = new Object[inputParameters.size()];

            // Build lookup keys
            for (int valueNum = 0; valueNum < inputParameters.size(); valueNum++)
            {
                Object parameterValue = validatedExprNodes[valueNum].evaluate(lookupEventsPerStream[row], true, exprEvaluatorContext);
                lookupValues[valueNum] = parameterValue;
            }

            EventTable[] result = null;

            // try the threadlocal iteration cache, if set
            if (localDataCache != null)
            {
                result = localDataCache.getCached(lookupValues);
            }

            // try the connection cache
            if (result == null)
            {
                result = dataCache.getCached(lookupValues);
                if ((result != null) && (localDataCache != null))
                {
                    localDataCache.put(lookupValues, result);
                }
            }

            if (result != null)     // found in cache
            {
                resultPerInputRow[row] = result;
            }
            else        // not found in cache, get from actual polling (db query)
            {
                try
                {
                    if (!strategyStarted)
                    {
                        pollExecStrategy.start();
                        strategyStarted = true;
                    }

                    // Poll using the polling execution strategy and lookup values
                    List<EventBean> pollResult = pollExecStrategy.poll(lookupValues, exprEvaluatorContext);

                    // index the result, if required, using an indexing strategy
                    EventTable[] indexTable = indexingStrategy.index(pollResult, dataCache.isActive(), statementContext);

                    // assign to row
                    resultPerInputRow[row] = indexTable;

                    // save in cache
                    dataCache.put(lookupValues, indexTable);

                    if (localDataCache != null)
                    {
                        localDataCache.put(lookupValues, indexTable);
                    }
                }
                catch (EPException ex)
                {
                    if (strategyStarted)
                    {
                        pollExecStrategy.done();
                    }
                    throw ex;
                }
            }
        }

        if (strategyStarted)
        {
            pollExecStrategy.done();
        }

        return resultPerInputRow;
    }

    public View addView(View view)
    {
        view.setParent(this);
        return view;
    }

    public View[] getViews()
    {
        return ViewSupport.EMPTY_VIEW_ARRAY;
    }

    public boolean removeView(View view)
    {
        throw new UnsupportedOperationException("Subviews not supported");
    }

    public void removeAllViews()
    {
        throw new UnsupportedOperationException("Subviews not supported");
    }

    public boolean hasViews()
    {
        return false;
    }

    public EventType getEventType()
    {
        return eventType;
    }

    public Iterator<EventBean> iterator()
    {
        EventTable[][] result = poll(NULL_ROWS, iteratorIndexingStrategy, exprEvaluatorContext);
        return new IterablesArrayIterator(result);
    }

    public SortedSet<Integer> getRequiredStreams()
    {
        return requiredStreams;
    }

    public boolean hasRequiredStreams()
    {
        return !requiredStreams.isEmpty();
    }
}
