package com.usthe.collector.dispatch;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.Expression;
import com.usthe.collector.collect.AbstractCollect;
import com.usthe.collector.collect.database.JdbcCommonCollect;
import com.usthe.collector.collect.http.HttpCollectImpl;
import com.usthe.collector.collect.icmp.IcmpCollectImpl;
import com.usthe.collector.collect.telnet.TelnetCollectImpl;
import com.usthe.collector.dispatch.timer.Timeout;
import com.usthe.collector.dispatch.timer.WheelTimerTask;
import com.usthe.common.entity.job.Job;
import com.usthe.common.entity.job.Metrics;
import com.usthe.common.entity.message.CollectRep;
import com.usthe.common.util.CommonConstants;
import com.usthe.common.util.CommonUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 指标组采集
 * @author tomsun28
 * @date 2021/10/10 15:35
 */
@Slf4j
@Data
public class MetricsCollect implements Runnable, Comparable<MetricsCollect> {
    /**
     * 监控ID
     */
    protected long monitorId;
    /**
     * 监控类型名称
     */
    protected String app;
    /**
     * 指标组配置
     */
    protected Metrics metrics;
    /**
     * 时间轮timeout
     */
    protected Timeout timeout;
    /**
     * 任务和数据调度
     */
    protected CollectDataDispatch collectDataDispatch;
    /**
     * 任务执行优先级
     */
    protected byte runPriority;
    /**
     * 是周期性采集还是一次性采集 true-周期性 false-一次性
     */
    protected boolean isCyclic;
    /**
     * 指标组采集任务新建时间
     */
    protected long newTime;
    /**
     * 指标组采集任务开始执行时间
     */
    protected long startTime;

    public MetricsCollect(Metrics metrics, Timeout timeout, CollectDataDispatch collectDataDispatch) {
        this.newTime = System.currentTimeMillis();
        this.timeout = timeout;
        this.metrics = metrics;
        WheelTimerTask timerJob = (WheelTimerTask) timeout.task();
        Job job = timerJob.getJob();
        this.monitorId = job.getMonitorId();
        this.app = job.getApp();
        this.collectDataDispatch = collectDataDispatch;
        this.isCyclic = job.isCyclic();
        // 临时一次性任务执行优先级高
        if (isCyclic) {
            runPriority = (byte) -1;
        } else {
            runPriority = (byte) 1;
        }
    }

    @Override
    public void run() {
        this.startTime = System.currentTimeMillis();
        setNewThreadName(monitorId, app, startTime, metrics);
        CollectRep.MetricsData.Builder response = CollectRep.MetricsData.newBuilder();
        response.setApp(app);
        response.setId(monitorId);
        response.setMetrics(metrics.getName());

        // 根据指标组采集协议,应用类型等来调度到真正的应用指标组采集实现类
        AbstractCollect abstractCollect = null;
        switch (metrics.getProtocol()) {
            case DispatchConstants.PROTOCOL_HTTP:
                abstractCollect = HttpCollectImpl.getInstance();
                break;
            case DispatchConstants.PROTOCOL_ICMP:
                abstractCollect = IcmpCollectImpl.getInstance();
                break;
            case DispatchConstants.PROTOCOL_TELNET:
                abstractCollect = TelnetCollectImpl.getInstance();
                break;
            case DispatchConstants.PROTOCOL_JDBC:
                abstractCollect = JdbcCommonCollect.getInstance();
                break;
                // todo
            default: break;
        }
        if (abstractCollect == null) {
            log.error("[Dispatcher] - not support this: app: {}, metrics: {}, protocol: {}.",
                    app, metrics.getName(), metrics.getProtocol());
            response.setCode(CollectRep.Code.FAIL);
            response.setMsg("not support " + app + ", "
                    + metrics.getName() + ", " + metrics.getProtocol());
            return;
        } else {
            try {
                abstractCollect.collect(response, monitorId, app, metrics);
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null && e.getCause() != null) {
                     msg = e.getCause().getMessage();
                }
                log.error("[Metrics Collect]: {}.", msg, e);
                response.setCode(CollectRep.Code.FAIL);
                if (msg != null) {
                    response.setMsg(msg);
                }
            }
        }
        // 别名属性表达式替换计算
        if (fastFailed()) {
            return;
        }
        calculateFields(metrics, response);
        CollectRep.MetricsData metricsData = validateResponse(response);
        collectDataDispatch.dispatchCollectData(timeout, metrics, metricsData);
    }


    /**
     * 根据 calculates 和 aliasFields 配置计算出真正的指标(fields)值
     * 计算instance实例值
     * @param metrics 指标组配置
     * @param collectData 采集数据
     */
    private void calculateFields(Metrics metrics, CollectRep.MetricsData.Builder collectData) {
        collectData.setPriority(metrics.getPriority());
        List<CollectRep.Field> fieldList = new LinkedList<>();
        for (Metrics.Field field : metrics.getFields()) {
            fieldList.add(CollectRep.Field.newBuilder().setName(field.getField()).setType(field.getType()).build());
        }
        collectData.addAllFields(fieldList);
        List<CollectRep.ValueRow> aliasRowList = collectData.getValuesList();
        if (aliasRowList == null || aliasRowList.isEmpty()) {
            return;
        }
        collectData.clearValues();
        // 先预处理 calculates
        if (metrics.getCalculates() == null) {
            metrics.setCalculates(Collections.emptyList());
        }
        Map<String, Expression> fieldExpressionMap = metrics.getCalculates()
                .stream()
                .map(cal -> {
                    int splitIndex = cal.indexOf("=");
                    String field = cal.substring(0, splitIndex);
                    String expressionStr = cal.substring(splitIndex + 1);
                    Expression expression = AviatorEvaluator.compile(expressionStr, true);
                    return new Object[]{field, expression}; })
                .collect(Collectors.toMap(arr -> (String)arr[0], arr -> (Expression) arr[1]));

        List<Metrics.Field> fields = metrics.getFields();
        List<String> aliasFields = metrics.getAliasFields();
        Map<String, String> aliasFieldValueMap = new HashMap<>(16);
        Map<String, Object> fieldValueMap = new HashMap<>(16);
        CollectRep.ValueRow.Builder realValueRowBuilder = CollectRep.ValueRow.newBuilder();
        for (CollectRep.ValueRow aliasRow : aliasRowList) {
            for (int aliasIndex = 0; aliasIndex < aliasFields.size(); aliasIndex++) {
                String aliasFieldValue = aliasRow.getColumns(aliasIndex);
                if (!CommonConstants.NULL_VALUE.equals(aliasFieldValue)) {
                    aliasFieldValueMap.put(aliasFields.get(aliasIndex), aliasFieldValue);
                }
            }
            StringBuilder instanceBuilder = new StringBuilder();
            for (Metrics.Field field : fields) {
                String realField = field.getField();
                Expression expression = fieldExpressionMap.get(realField);
                String value = null;
                if (expression != null) {
                    // 存在计算表达式 则计算值
                    if (CommonConstants.TYPE_NUMBER == field.getType()) {
                        for (String variable : expression.getVariableNames()) {
                            Double doubleValue = CommonUtil.parseDoubleStr(aliasFieldValueMap.get(variable));
                            if (doubleValue != null) {
                                fieldValueMap.put(variable, doubleValue);
                            }
                        }
                    } else {
                        for (String variable : expression.getVariableNames()) {
                            String strValue = aliasFieldValueMap.get(variable);
                            if (strValue != null && !"".equals(strValue)) {
                                fieldValueMap.put(variable, strValue);
                            }
                        }
                    }
                    try {
                        Object objValue = expression.execute(fieldValueMap);
                        if (objValue != null) {
                            value = String.valueOf(objValue);
                        }
                    } catch (Exception e) {
                        log.warn(e.getMessage());
                    }
                } else {
                    // 不存在 则映射别名值
                    value = aliasFieldValueMap.get(realField);
                }
                if (value == null) {
                    value = CommonConstants.NULL_VALUE;
                }
                realValueRowBuilder.addColumns(value);
                fieldValueMap.clear();
                if (field.isInstance() && !CommonConstants.NULL_VALUE.equals(value)) {
                    instanceBuilder.append(value);
                }
            }
            aliasFieldValueMap.clear();
            // 设置实例instance
            realValueRowBuilder.setInstance(instanceBuilder.toString());
            collectData.addValues(realValueRowBuilder.build());
            realValueRowBuilder.clear();
        }
    }

    private boolean fastFailed() {
        return this.timeout == null || this.timeout.isCancelled();
    }

    private CollectRep.MetricsData validateResponse(CollectRep.MetricsData.Builder builder) {
        long endTime = System.currentTimeMillis();
        builder.setTime(endTime);
        log.debug("[Collect]: newTime: {}, startTime: {}, spendTime: {}.", newTime, startTime, endTime - startTime);
        if (builder.getCode() != CollectRep.Code.SUCCESS) {
            log.info("[Collect Fail] Reason: {}", builder.getMsg());
        } else {
            log.info("[Collect Success].");
        }
        return builder.build();
    }

    private void setNewThreadName(long monitorId, String app, long startTime, Metrics metrics) {
        String builder = monitorId + "-" + app + "-" + metrics.getName() +
                "-" + String.valueOf(startTime).substring(9);
        Thread.currentThread().setName(builder);
    }

    @Override
    public int compareTo(MetricsCollect collect) {
        return runPriority - collect.runPriority;
    }
}
