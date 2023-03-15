package com.kingdom.service.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.kingdom.acceptancereport.constants.KingDeeContents;
import com.kingdom.administrative.dao.DataFromK3Dao;
import com.kingdom.contract.model.PermissionResultData;
import com.kingdom.dao.*;
import com.kingdom.dockingK3cloud.service.imp.DockingK3ServiceImpl;
import com.kingdom.entity.SubcontractingProcessEntity;
import com.kingdom.entity.TSubcontractDetailChange;
import com.kingdom.entity.TSubcontractDetailContract;
import com.kingdom.entity.TSubcontractDetailPayment;
import com.kingdom.ess.common.OaWorkflowService;
import com.kingdom.message.CommonMessage;
import com.kingdom.model.PaymentRequest;
import com.kingdom.permission.dao.UserAgreementDao;
import com.kingdom.permission.enumP.MenuNoEnum;
import com.kingdom.permission.service.UserPermissionService;
import com.kingdom.service.SubcontractingDockingKingdee;
import com.kingdom.util.*;
import com.kingdom.vo.SubcontractPlatformShowVO;
import com.szkingdom.koca.auth.bean.TrustedPrincipal;
import com.szkingdom.koca.auth.context.AuthContextHolder;
import com.szkingdom.koca.core.protocol.ListResult;
import com.szkingdom.koca.core.protocol.PageListResult;
import com.szkingdom.koca.core.protocol.Result;
import com.szkingdom.koca.core.util.LogUtils;
import com.szkingdom.koca.support.datasource.transmanage.TransManager;
import org.codehaus.xfire.client.Client;
import org.codehaus.xfire.client.XFireProxy;
import org.codehaus.xfire.client.XFireProxyFactory;
import org.codehaus.xfire.service.binding.ObjectServiceFactory;
import org.springframework.stereotype.Service;
import weaver.workflow.webservices.*;

import javax.annotation.Resource;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 转包对接金蝶接口
 * 设备类销售订单（采购）->采购申请单->采购订单（？）【需记录销售订单号，采购申请单号、采购订单号】
 * 服务类->采购订单（采购）
 * 流程存在多条明细时，一条明细生成一个订单
 */
@Service
public class SubcontractingDockingKingdeeImpl implements SubcontractingDockingKingdee {

    @Resource
    private SubcontractingProcessDao subcontractingProcessDao;

    @Resource
    private DataFromK3Dao dataFromK3Dao;

    @Resource
    private DockingK3ServiceImpl dockingK3Service;

    @Resource
    private SubcontractCommonDao subcontractCommonDao;

    @Resource
    private CommonDao commonDao;

    @Resource
    private DataFromOA dataFromOA;

    @Resource
    private OaWorkflowService oaWorkflowService;

    @Resource
    private KingDeeDataDao deeDataDao;

    @Resource
    protected UserPermissionService userPermissionService;

    @Resource
    protected UserAgreementDao userAgreementDao;

    /**
     * 对接销售订单/采购订单
     * */
    @Override
    public Result subcontractingDockingKingdee() {
        List<SubcontractingProcessEntity> list = subcontractingProcessDao.SubcontractingProcessDate();
        list.forEach(spe -> {
            try {
                TSubcontractDetailContract subcontractDetailContract = new TSubcontractDetailContract();
                subcontractDetailContract.setDockingStatus("0");
                subcontractDetailContract.setDetailId(spe.getDetailId());
                String paramData = "";
                String tableName = "";
                String detailData = "";
                if (Constants.ZERO.equals(spe.getBusinessType())) {
                    // 设备类
                    CreateSaleOrder createSaleOrder = new CreateSaleOrder();
                    detailData = createSaleOrder.saleOrderDet(spe.getK3MaterialCode(), spe.getQuantity(), "",
                            dataFromK3Dao.findUnitCode(spe.getK3MaterialCode()));
                    paramData = createSaleOrder.param(spe.getK3Organization(), spe.getK3Contract(),
                            spe.getK3SalesDepartments(), spe.getK3Staff(), detailData, spe.getK3CustomerCode());
                    tableName = "SAL_SaleOrder";
                } else {
                    // 服务类
                    CreatePurchaseOrder createPurchaseOrder = new CreatePurchaseOrder();
                    detailData = "[" + createPurchaseOrder.dtParam(spe.getK3MaterialCode(), spe.getQuantity(), spe.getUnitPrice(),
                            spe.getTaxInclusiveUnitPrice(), spe.getTaxRate(), DateUtil.getNextMonthDay()) + "]";
                    paramData = createPurchaseOrder.orderParam(spe.getK3Organization(), spe.getSupplier(), spe.getK3SalesDepartments(),
                            spe.getCurrencyType(), spe.getK3Contract(), detailData, "1183", "1");
                    tableName = "PUR_PurchaseOrder";
                }
                String returnMessage = dockingK3Service.requestKingdeeTosave(paramData, false, false, tableName);
                K3WebApi k3WebApi = new K3WebApi();
                JSONObject responseJson = k3WebApi.analysisJson(returnMessage);
                if("200".equals(responseJson.getString("code"))){
                    subcontractDetailContract.setDockingStatus("0");
                    if (Constants.ZERO.equals(spe.getBusinessType())) {
                        // 销售订单
                        subcontractDetailContract.setSalesOrder(responseJson.getString("order"));
                        // 下推采购申请单
                        PushDown pushDown = new PushDown();
                        JSONObject pushJson = pushDown.pushDown(responseJson.getString("id"), tableName, "PUR_Requisition");
                        this.submitAudit(pushJson.getString("id"), "PUR_Requisition");
                        subcontractDetailContract.setPurchaseRequisition(pushJson.getString("order"));
                        LogUtils.info("转包创建销售订单下推采购申请单成功，销售订单："+responseJson.getString("order")
                                +",采购申请单："+pushJson.getString("order"));
                    } else {
                        subcontractDetailContract.setPurchaseOrder(responseJson.getString("order"));
                        subcontractDetailContract.setPurchaseDate(DateUtil.getStringDate());
                        subcontractDetailContract.setDockingStatus("0");
                    }
                    try {
                        TransManager.startTrans();
                        subcontractCommonDao.updateSubcontractDetailContract(subcontractDetailContract);
                        TransManager.commitTrans();
                    }catch (Exception e){
                        TransManager.rollbackTrans();
                        LogUtils.error("exceptions InGenerating Records:",e);
                    }
                }else {
                    LogUtils.error("转包创建订单失败"+analysisErrorMessage(returnMessage));
                }
            }catch (Exception e){
                LogUtils.error("转包对接金蝶", e);
            }
        });
        return Result.success("已完成对接");
    }

    /**
     * 归档数据写入台账
     * */
    @Override
    public Result arcDataIsWritToTheLedger() {
        try{
            subcontractCommonDao.synSubcontractMain();
            subcontractCommonDao.synSubcontractDetailContract();
            return Result.success("同步完成");
        }catch (Exception e){
            LogUtils.error("转包同步归档数据失败", e);
            return Result.success("同步失败");
        }
    }

    @Override
    public Result updateTheAccPaymentAmount() {
        List<Map<String,String>> list = subcontractCommonDao.querySubcontractPurchaseOrder();
        list.forEach(order ->{
            try {
                PaymentRequest paymentRequest = new PaymentRequest();
                paymentRequest.setOrderNumber(order.get("purchaseOrder"));
                Map<String, Object> updateMap_detail = new HashMap<>();
                Map<String, Object> whereMap = new HashMap<>();
                whereMap.put("tenant_id",order.get("tenantId"));
                updateMap_detail.put("accupaid_amount",deeDataDao.findTotalAmountPaid(paymentRequest).toString());
                TransManager.startTrans();
                commonDao.updateByWhere("t_subcontract_detail_contract", MapToList.toList(updateMap_detail),
                        MapToList.toList(whereMap));
                TransManager.commitTrans();
            }catch (Exception e){
                TransManager.rollbackTrans();
                LogUtils.error("updateTheAccPaymentAmount",e);
            }
        });
        return Result.success("更新已完成");
    }

    @Override
    public PageListResult<SubcontractPlatformShowVO> queryPageSubPlatform(SubcontractPlatformShowVO subcontractPlatformShowVO) {
        PermissionResultData permissionResultData =
                userPermissionService.generalPermissions(MenuNoEnum.SUBCONTRACT_PLATFORM, 0);
        String isAdmin = permissionResultData.getAdmin();
        // 数据边界
        String canDataBorder = permissionResultData.getCanDataBorder();
        TrustedPrincipal principal = AuthContextHolder.getPrincipal();
        String createNo = principal.getUserNo();
        if(isAdmin.equals("0")){
            // 是否有调用接口权限
            String permission = permissionResultData.getPermission();
            if(permission.equals("0")){
                return new PageListResult<>();
            }
            // 数据边界部门
            if(canDataBorder.equals("0")){
                Integer saleDepartment = userAgreementDao.getSaleDepartment(createNo);
                subcontractPlatformShowVO.setSalesDepartment(saleDepartment.toString());
            }
        }
        List<SubcontractPlatformShowVO> list = subcontractCommonDao.queryPageSubcontractPlatformShowVO(subcontractPlatformShowVO);
        list.forEach(sps -> {
            // 累计收款金额
            sps.setColltotalAmount(subcontractCommonDao.accumulatedCollection(sps.getMainSalesContract()));
        });
        return new PageListResult<>(list);
    }

    @Override
    public ListResult<TSubcontractDetailContract> querySubcontractDetailContract(String requestId) {
        List<TSubcontractDetailContract> list = subcontractCommonDao.querySubcontractDetailContract(requestId);
        return new ListResult<>(list);
    }

    @Override
    public PageListResult<TSubcontractDetailPayment> queryPaymentDetail(String id) {
        List<TSubcontractDetailPayment> list = subcontractCommonDao.queryPaymentDetail(id);
        return new PageListResult<>(list);
    }

    // 新增付款
    @Override
    public Result savePaymentDetail(Map<String, Object> map) {
        String whetherPaymentIsPayable = subcontractCommonDao.payableAmount(map.get("detailId").toString());
        if("0".equals(whetherPaymentIsPayable)){
            return Result.fail("申请付款金额已超出未付款金额");
        }
        TSubcontractDetailPayment subcontractDetailPayment = new TSubcontractDetailPayment();
        String userName = AuthContextHolder.getPrincipal().getUserName();
        String userCode = AuthContextHolder.getPrincipal().getUserCode();
        map.put("userCode",userCode);
        if("admin".equals(userCode)){
            subcontractDetailPayment.setCreatedBy("13004");
            map.put("userCode","13004");
        }
        subcontractDetailPayment.setApplicant(userName);
        subcontractDetailPayment.setCreatedBy(userCode);
        subcontractDetailPayment.setState("1");
        subcontractDetailPayment.setPurchaseOrder(map.get("purchaseOrder").toString());
        subcontractDetailPayment.setRequestedPaymentAmount(map.get("requestedPaymentAmount").toString());
        subcontractDetailPayment.setMainId(map.get("mainId").toString());
        subcontractDetailPayment.setContractDetails(map.get("detailId").toString());
        try {
            String requestOa = abutmentOAwithPayment(map);
            JSONObject jsonObject = JSON.parseObject(requestOa);
            String code = "-1";
            String requestid = "-1";
            String msg = "";
            String msgArray = jsonObject.getString("msg");
            JSONArray jarr = JSON.parseArray(msgArray);
            for (int k = 0; k < jarr.size(); k++) {
                JSONObject project = JSONObject.parseObject(jarr.get(k) + "");
                code = project.getString("code");
                requestid = project.getString("requestid");
                subcontractDetailPayment.setRequestId(requestid);
                msg = project.getString("msg");
            }
            if (Constants.STATUS_CODE_SUCCESS.equals(code)) {
                subcontractCommonDao.insertDetailPayment(subcontractDetailPayment);
                return Result.success(CommonMessage.SUCCESS_MESSAGE);
            } else {
                return Result.fail(msg);
            }
        }catch (Exception e){
            LogUtils.error("转包生成CG005",e);
            return Result.fail("转包生成CG005异常");
        }
    }

    // 付款无效
    @Override
    public Result invalidPayment(Map<String, Object> map) {
        try {
            if (null != map && map.size() > 0 && map.containsKey("requestId")) {
                List list = dataFromOA.queryPaymentProcess(map.get("requestId").toString());
                if (null != list && list.size() > 0) {
                    return Result.fail("请先在OA中删除付款流程，再设置为无效付款！");
                }
                Map<String, Object> updateMap_detail = new HashMap<>();
                updateMap_detail.put("state","0");
                Map<String, Object> whereMap = new HashMap<>();
                whereMap.put("tenant_id",map.get("payId").toString());
                TransManager.startTrans();
                commonDao.updateByWhere(Constants.TABLE_T_SUBCONTRACT_DETAIL_PAYMENT, MapToList.toList(updateMap_detail),
                        MapToList.toList(whereMap));
                TransManager.commitTrans();
                return Result.success(CommonMessage.SUCCESS_MESSAGE);
            }
        }catch (Exception e){
            TransManager.rollbackTrans();
            return Result.fail(CommonMessage.FAILURE_MESSAGE);
        }
        return Result.fail(CommonMessage.ERROR_NO_PARAMTERS_PASS_IN_MESSAGE);
    }

    // 启用付款
    @Override
    public Result effectivePayment(Map<String, Object> map) {
        try {
            String whetherPaymentIsPayable = subcontractCommonDao.payableAmount(map.get("detailId").toString());
            if("0".equals(whetherPaymentIsPayable)){
                return Result.fail("申请付款金额已超出未付款金额");
            }
            String userCode = AuthContextHolder.getPrincipal().getUserCode();
            map.put("userCode",userCode);
            if("admin".equals(userCode)){
                map.put("userCode","13004");
            }
            String requestOa = abutmentOAwithPayment(map);
            JSONObject jsonObject = JSON.parseObject(requestOa);
            String code = "-1";
            String requestid = "-1";
            String msg = "";
            String msgArray = jsonObject.getString("msg");
            JSONArray jarr = JSON.parseArray(msgArray);
            Map<String, Object> updateMap_detail = new HashMap<>();
            for (int k = 0; k < jarr.size(); k++) {
                JSONObject project = JSONObject.parseObject(jarr.get(k) + "");
                code = project.getString("code");
                requestid = project.getString("requestid");
                updateMap_detail.put("request_id",requestid);
                msg = project.getString("msg");
            }
            if (!Constants.STATUS_CODE_SUCCESS.equals(code)) {
                 return Result.fail(msg);
            }
            updateMap_detail.put("state","1");
            Map<String, Object> whereMap = new HashMap<>();
            whereMap.put("tenant_id",map.get("payId").toString());
            commonDao.updateByWhere(Constants.TABLE_T_SUBCONTRACT_DETAIL_PAYMENT, MapToList.toList(updateMap_detail),
                    MapToList.toList(whereMap));
            return Result.success(CommonMessage.SUCCESS_MESSAGE);
        }catch (Exception e){
            e.printStackTrace();
            return Result.fail(CommonMessage.FAILURE_MESSAGE);
        }
    }

    @Override
    public PageListResult<TSubcontractDetailChange> queryChangeDetail(String id) {
        List<TSubcontractDetailChange> list = subcontractCommonDao.queryChangeDetail(id);
        return new PageListResult<>(list);
    }

    // 新增变更
    @Override
    public Result saveChangeDetail(Map<String, Object> map) {
        try {
            String requestId = this.createJy006(map);
            if (requestId.length() <= 1) {
                return Result.fail("创建流程失败");
            }

            String userName = AuthContextHolder.getPrincipal().getUserName();
            TSubcontractDetailChange tSubcontractDetailChange = new TSubcontractDetailChange();
            tSubcontractDetailChange.setCreatedBy(userName);
            tSubcontractDetailChange.setCcnos(getString(map, "ccnos"));
            tSubcontractDetailChange.setAfterChange(getString(map, "afterChange"));
            tSubcontractDetailChange.setBeforeChange(getString(map, "beforeChange"));
            tSubcontractDetailChange.setChangeAmount(getString(map, "changeAmount"));
            tSubcontractDetailChange.setReasonForChange(getString(map, "reasonForChange"));
            tSubcontractDetailChange.setPurchaseOrder(getString(map, "purchaseOrder"));
            tSubcontractDetailChange.setRequestId(requestId);
            tSubcontractDetailChange.setState("1");
            tSubcontractDetailChange.setMainId(getString(map, "mainId"));
            tSubcontractDetailChange.setContractDetails(getString(map, "detailId"));

            subcontractCommonDao.insertChangeDetail(tSubcontractDetailChange);

            String detailId = getString(map, "detailId");
            String changeAmount = subcontractCommonDao.totalAmountOfChange(detailId);
            Map<String, Object> updateMap = new HashMap<>();
            updateMap.put("change_amount", changeAmount);

            Map<String, Object> whereMap = new HashMap<>();
            whereMap.put("contract_details", detailId);

            commonDao.updateByWhere("t_subcontract_detail_change", MapToList.toList(updateMap),
                    MapToList.toList(whereMap));

            return Result.success(CommonMessage.SUCCESS_MESSAGE);
        } catch (Exception e) {
            LogUtils.error("生成转包变更流程失败", e);
            return Result.fail("创建流程异常");
        }
    }

    private String getString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : value.toString();
    }


    // 作废变更
    @Override
    public Result nullificationChange(Map<String, Object> map) {
        try {
            if (null != map && map.size() > 0 && map.containsKey("requestId")) {
                List list = dataFromOA.queryPaymentProcess(map.get("requestId").toString());
                if (null != list && list.size() > 0) {
                    return Result.fail("请先在OA中删除付款流程，再设置为无效变更！");
                }
                Map<String, Object> updateMap_detail = new HashMap<>();
                updateMap_detail.put("state","0");
                Map<String, Object> whereMap = new HashMap<>();
                whereMap.put("tenant_id",map.get("payId").toString());
                TransManager.startTrans();
                commonDao.updateByWhere("t_subcontract_detail_change", MapToList.toList(updateMap_detail),
                        MapToList.toList(whereMap));
                TransManager.commitTrans();
                return Result.success(CommonMessage.SUCCESS_MESSAGE);
            }
        }catch (Exception e){
            e.printStackTrace();
            TransManager.rollbackTrans();
            return Result.fail(CommonMessage.FAILURE_MESSAGE);
        }
        return Result.fail(CommonMessage.ERROR_NO_PARAMTERS_PASS_IN_MESSAGE);
    }

    // 启用变更
    @Override
    public Result enableChange(Map<String, Object> map) {
        try{

            String requestId = this.createJy006(map);
            if(requestId.length()<=1){
                return  Result.fail("创建流程失败");
            }
            Map<String, Object> updateMap_detail = new HashMap<>();
            updateMap_detail.put("state","1");
            Map<String, Object> whereMap = new HashMap<>();
            whereMap.put("tenant_id",map.get("tenantId").toString());
            updateMap_detail.put("request_id",requestId);
            TransManager.startTrans();
            commonDao.updateByWhere("t_subcontract_detail_change", MapToList.toList(updateMap_detail),
                    MapToList.toList(whereMap));
            TransManager.commitTrans();
            String changeAmount = subcontractCommonDao.totalAmountOfChange(map.get("detailId").toString());
            updateMap_detail = new HashMap<>();
            updateMap_detail.put("change_amount",changeAmount);
            whereMap = new HashMap<>();
            whereMap.put("contract_details",map.get("detailId").toString());
            commonDao.updateByWhere("t_subcontract_detail_change", MapToList.toList(updateMap_detail),
                    MapToList.toList(whereMap));
            return Result.success(CommonMessage.SUCCESS_MESSAGE);

        }catch (Exception e){
            e.printStackTrace();
            TransManager.rollbackTrans();
            return Result.fail(CommonMessage.FAILURE_MESSAGE);
        }
    }

//    @Autowired
//    private JdbcTemplate jdbcTemplate;
//
//    public Result enableChange(Map<String, Object> map) {
//        try {
//            String requestId = this.createJy006(map);
//            if (requestId.length() <= 1) {
//                return Result.fail("创建流程失败");
//            }
//
//            String detailId = map.get("detailId").toString();
//            String tenantId = map.get("tenantId").toString();
//            String changeAmount = subcontractCommonDao.totalAmountOfChange(detailId);
//
//            // 使用JdbcTemplate执行UPDATE语句
//            jdbcTemplate.update("UPDATE t_subcontract_detail_change SET state = ?, request_id = ?, change_amount = ? WHERE tenant_id = ? AND contract_details = ?",
//                    1, requestId, changeAmount, tenantId, detailId);
//
//            return Result.success(CommonMessage.SUCCESS_MESSAGE);
//        } catch (Exception e) {
//            e.printStackTrace();
//            return Result.fail(CommonMessage.FAILURE_MESSAGE);
//        }
//    }



    public String createJy006(Map<String,Object> map) throws Exception{
        Map<String,Object> whereMap = new HashMap<>();
        whereMap.put("requestid",map.get("requestId"));
        Map<String,Object> objectMap = commonDao.queryByWhere("datadw.ods_oa_kingdom_FORMTABLE_MAIN_140",MapToList.toList(whereMap));
        String mainContract = objectMap.get("JINZZHTH").toString();
        whereMap =  new HashMap<>();
        String userCode = AuthContextHolder.getPrincipal().getUserCode();
        if("admin".equals(userCode)){
            userCode = "13004";
        }
        whereMap.put("workcode",userCode);
        Map<String,Object> userMap = commonDao.queryByWhere("datadw.ods_oa_kingdom_HRMRESOURCE",MapToList.toList(whereMap));
        String userId = userMap.get("ID").toString();
        whereMap =  new HashMap<>();
        whereMap.put("id",userMap.get("DEPARTMENTID").toString());
        Map<String,Object> deptMap = commonDao.queryByWhere("datadw.ods_oa_kingdom_HRMDEPARTMENT",MapToList.toList(whereMap));
        // 部门
        String dept = deptMap.get("ID").toString();
        // 上级部门
        String supDept = deptMap.get("SUPDEPID").toString();
        //工作流信息
        WorkflowBaseInfo workflowBaseInfo = new WorkflowBaseInfo();
        //流程ID
        workflowBaseInfo.setWorkflowId("6581");
        //流程名称
        workflowBaseInfo.setWorkflowName("JY006-变更转包申请");
        WorkflowRequestInfo workflowRequestInfo = new WorkflowRequestInfo();
        //显示
        workflowRequestInfo.setCanView(true);

        workflowRequestInfo.setIsnextflow("0");
        //可编辑
        workflowRequestInfo.setCanEdit(true);
        // 请求标题 姓名-日期
        workflowRequestInfo.setRequestName(workflowBaseInfo.getWorkflowName()+map.get("purchaseOrder").toString());
        //紧急程度 0：正常 1：重要 2：紧急
        workflowRequestInfo.setRequestLevel("0");
        //创建者ID 创建流程时为必输项
        workflowRequestInfo.setCreatorId(userId);
        //工作流信息
        workflowRequestInfo.setWorkflowBaseInfo(workflowBaseInfo);
        //主表
        WorkflowMainTableInfo workflowMainTableInfo = new WorkflowMainTableInfo();
        //主表字段只有一条记录
        WorkflowRequestTableRecord[] workflowRequestTableRecord = new WorkflowRequestTableRecord[1];
        //主表的3个字段
        WorkflowRequestTableField[] WorkflowRequestTableField = new WorkflowRequestTableField[24];
        // 姓名
        WorkflowRequestTableField[0] = new WorkflowRequestTableField();
        WorkflowRequestTableField[0].setFieldName("shenqr");
        WorkflowRequestTableField[0].setFieldValue(userId);
        WorkflowRequestTableField[0].setView(true);
        WorkflowRequestTableField[0].setEdit(true);
        // 子公司对应合同号
        WorkflowRequestTableField[1] = new WorkflowRequestTableField();
        WorkflowRequestTableField[1].setFieldName("sub_contr");
        WorkflowRequestTableField[1].setFieldValue(map.get("ccnos").toString());
        WorkflowRequestTableField[1].setView(true);
        WorkflowRequestTableField[1].setEdit(true);
        // 业务类型
        WorkflowRequestTableField[2] = new WorkflowRequestTableField();
        WorkflowRequestTableField[2].setFieldName("business_type");
        WorkflowRequestTableField[2].setFieldValue("0");
        WorkflowRequestTableField[2].setView(true);
        WorkflowRequestTableField[2].setEdit(true);
        // 金证主合同号(变更转包)
        WorkflowRequestTableField[3] = new WorkflowRequestTableField();
        WorkflowRequestTableField[3].setFieldName("jinzzhth");
        WorkflowRequestTableField[3].setFieldValue(mainContract);
        WorkflowRequestTableField[3].setView(true);
        WorkflowRequestTableField[3].setEdit(true);
        // 合同类型
        WorkflowRequestTableField[4] = new WorkflowRequestTableField();
        WorkflowRequestTableField[4].setFieldName("hetlx");
        WorkflowRequestTableField[4].setFieldValue(map.get("contractType").toString());
        WorkflowRequestTableField[4].setView(true);
        WorkflowRequestTableField[4].setEdit(true);
        // 流水号
        WorkflowRequestTableField[5] = new WorkflowRequestTableField();
        WorkflowRequestTableField[5].setFieldName("liush");
        WorkflowRequestTableField[5].setFieldValue(map.get("purchaseOrder").toString());
        WorkflowRequestTableField[5].setView(true);
        WorkflowRequestTableField[5].setEdit(true);
        // 部门
        WorkflowRequestTableField[6] = new WorkflowRequestTableField();
        WorkflowRequestTableField[6].setFieldName("bum");
        WorkflowRequestTableField[6].setFieldValue(dept);
        WorkflowRequestTableField[6].setView(true);
        WorkflowRequestTableField[6].setEdit(true);
        // 上级部门
        WorkflowRequestTableField[7] = new WorkflowRequestTableField();
        WorkflowRequestTableField[7].setFieldName("shangjbm");
        WorkflowRequestTableField[7].setFieldValue(supDept);
        WorkflowRequestTableField[7].setView(true);
        WorkflowRequestTableField[7].setEdit(true);
        // 填报日期
        WorkflowRequestTableField[8] = new WorkflowRequestTableField();
        WorkflowRequestTableField[8].setFieldName("tianbrq");
        WorkflowRequestTableField[8].setFieldValue(DateUtil.getStringDateShort());
        WorkflowRequestTableField[8].setView(true);
        WorkflowRequestTableField[8].setEdit(true);
        // 主合同客户名称
        WorkflowRequestTableField[9] = new WorkflowRequestTableField();
        WorkflowRequestTableField[9].setFieldName("zhuhtkhmc");
        WorkflowRequestTableField[9].setFieldValue(objectMap.get("ZHUHTKHMC").toString());
        WorkflowRequestTableField[9].setView(true);
        WorkflowRequestTableField[9].setEdit(true);
        // 主合同金额
        WorkflowRequestTableField[10] = new WorkflowRequestTableField();
        WorkflowRequestTableField[10].setFieldName("zhuhtje");
        WorkflowRequestTableField[10].setFieldValue(objectMap.get("ZHUHTJE").toString());
        WorkflowRequestTableField[10].setView(true);
        WorkflowRequestTableField[10].setEdit(true);
        // 主合同金额
        WorkflowRequestTableField[11] = new WorkflowRequestTableField();
        WorkflowRequestTableField[11].setFieldName("xiaosbmspr");
        WorkflowRequestTableField[11].setFieldValue(objectMap.get("XIAOSBMSPR").toString());
        WorkflowRequestTableField[11].setView(true);
        WorkflowRequestTableField[11].setEdit(true);
        //
        WorkflowRequestTableField[12] = new WorkflowRequestTableField();
        WorkflowRequestTableField[12].setFieldName("xiaosbm");
        WorkflowRequestTableField[12].setFieldValue(objectMap.get("XIAOSBM").toString());
        WorkflowRequestTableField[12].setView(true);
        WorkflowRequestTableField[12].setEdit(true);
        //
        WorkflowRequestTableField[13] = new WorkflowRequestTableField();
        WorkflowRequestTableField[13].setFieldName("zhuanbms");
        WorkflowRequestTableField[13].setFieldValue(objectMap.get("ZHUANBMS").toString());
        WorkflowRequestTableField[13].setView(true);
        WorkflowRequestTableField[13].setEdit(true);

        WorkflowRequestTableField[14] = new WorkflowRequestTableField();
        WorkflowRequestTableField[14].setFieldName("zhuhtkzsl");
        WorkflowRequestTableField[14].setFieldValue(objectMap.get("ZHUHTKZSL").toString());
        WorkflowRequestTableField[14].setView(true);
        WorkflowRequestTableField[14].setEdit(true);

        WorkflowRequestTableField[15] = new WorkflowRequestTableField();
        WorkflowRequestTableField[15].setFieldName("caigzz");
        WorkflowRequestTableField[15].setFieldValue(objectMap.get("CAIGZZ").toString());
        WorkflowRequestTableField[15].setView(true);
        WorkflowRequestTableField[15].setEdit(true);

        WorkflowRequestTableField[16] = new WorkflowRequestTableField();
        WorkflowRequestTableField[16].setFieldName("chanpbmspr");
        WorkflowRequestTableField[16].setFieldValue(objectMap.get("CHANPBMSPR").toString());
        WorkflowRequestTableField[16].setView(true);
        WorkflowRequestTableField[16].setEdit(true);

        WorkflowRequestTableField[17] = new WorkflowRequestTableField();
        WorkflowRequestTableField[17].setFieldName("zhuhtkpsl");
        WorkflowRequestTableField[17].setFieldValue(objectMap.get("ZHUHTKPSL").toString());
        WorkflowRequestTableField[17].setView(true);
        WorkflowRequestTableField[17].setEdit(true);

        WorkflowRequestTableField[18] = new WorkflowRequestTableField();
        WorkflowRequestTableField[18].setFieldName("chanpbm");
        WorkflowRequestTableField[18].setFieldValue(objectMap.get("CHANPBM").toString());
        WorkflowRequestTableField[18].setView(true);
        WorkflowRequestTableField[18].setEdit(true);

        WorkflowRequestTableField[19] = new WorkflowRequestTableField();
        WorkflowRequestTableField[19].setFieldName("k3xiaosbm");
        WorkflowRequestTableField[19].setFieldValue(objectMap.get("K3XIAOSBM").toString());
        WorkflowRequestTableField[19].setView(true);
        WorkflowRequestTableField[19].setEdit(true);

        WorkflowRequestTableField[20] = new WorkflowRequestTableField();
        WorkflowRequestTableField[20].setFieldName("k3ht");
        WorkflowRequestTableField[20].setFieldValue(objectMap.get("K3HT").toString());
        WorkflowRequestTableField[20].setView(true);
        WorkflowRequestTableField[20].setEdit(true);

        WorkflowRequestTableField[22] = new WorkflowRequestTableField();
        WorkflowRequestTableField[22].setFieldName("k3gys");
        WorkflowRequestTableField[22].setFieldValue(objectMap.get("K3GYS").toString());
        WorkflowRequestTableField[22].setView(true);
        WorkflowRequestTableField[22].setEdit(true);

        WorkflowRequestTableField[23] = new WorkflowRequestTableField();
        WorkflowRequestTableField[23].setFieldName("k3cgy");
        WorkflowRequestTableField[23].setFieldValue(objectMap.get("K3CGY").toString());
        WorkflowRequestTableField[23].setView(true);
        WorkflowRequestTableField[23].setEdit(true);

        workflowRequestTableRecord[0] = new WorkflowRequestTableRecord();
        workflowRequestTableRecord[0].setWorkflowRequestTableFields(WorkflowRequestTableField);
        workflowMainTableInfo.setRequestRecords(workflowRequestTableRecord);
        /****************main table end*************/
        // 明细表
        /****************detail table start*************/
        WorkflowDetailTableInfo[] workflowDetailTableInfos = new WorkflowDetailTableInfo[3];

        workflowRequestTableRecord = new WorkflowRequestTableRecord[1];
        WorkflowRequestTableField = new WorkflowRequestTableField[7];
        // 采购订单
        WorkflowRequestTableField[0] = new WorkflowRequestTableField();
        WorkflowRequestTableField[0].setFieldName("purchase_order_no");
        WorkflowRequestTableField[0].setFieldValue(map.get("purchaseOrder").toString());
        WorkflowRequestTableField[0].setView(true);
        WorkflowRequestTableField[0].setEdit(true);
        // 变更前金额
        WorkflowRequestTableField[1] = new WorkflowRequestTableField();
        WorkflowRequestTableField[1].setFieldName("contr_amount_before");
        WorkflowRequestTableField[1].setFieldValue(map.get("beforeChange").toString());
        WorkflowRequestTableField[1].setView(true);
        WorkflowRequestTableField[1].setEdit(true);
        // 变更后金额
        WorkflowRequestTableField[2] = new WorkflowRequestTableField();
        WorkflowRequestTableField[2].setFieldName("contr_amount_after");
        WorkflowRequestTableField[2].setFieldValue(map.get("afterChange").toString());
        WorkflowRequestTableField[2].setView(true);
        WorkflowRequestTableField[2].setEdit(true);
        // 变更金额
        WorkflowRequestTableField[3] = new WorkflowRequestTableField();
        WorkflowRequestTableField[3].setFieldName("change_amount");
        WorkflowRequestTableField[3].setFieldValue(map.get("changeAmount").toString());
        WorkflowRequestTableField[3].setView(true);
        WorkflowRequestTableField[3].setEdit(true);
        // 变更原因
        WorkflowRequestTableField[4] = new WorkflowRequestTableField();
        WorkflowRequestTableField[4].setFieldName("change_reason");
        WorkflowRequestTableField[4].setFieldValue(map.get("reasonForChange").toString());
        WorkflowRequestTableField[4].setView(true);
        WorkflowRequestTableField[4].setEdit(true);
        // 业务类型
        WorkflowRequestTableField[5] = new WorkflowRequestTableField();
        WorkflowRequestTableField[5].setFieldName("business_type");
        WorkflowRequestTableField[5].setFieldValue("0");
        WorkflowRequestTableField[5].setView(true);
        WorkflowRequestTableField[5].setEdit(true);
        // 税率
        WorkflowRequestTableField[6] = new WorkflowRequestTableField();
        WorkflowRequestTableField[6].setFieldName("tax_rate");
        WorkflowRequestTableField[6].setFieldValue("");
        WorkflowRequestTableField[6].setView(true);
        WorkflowRequestTableField[6].setEdit(true);
        workflowDetailTableInfos[2] =new WorkflowDetailTableInfo();
//        workflowDetailTableInfos[0].setTableDBName("formtable_main_533_dt3");
        workflowDetailTableInfos[2].setTableDBName("formtable_main_754_dt3");

        workflowRequestTableRecord[0] = new WorkflowRequestTableRecord();
        workflowRequestTableRecord[0].setWorkflowRequestTableFields(WorkflowRequestTableField);
        workflowDetailTableInfos[2].setWorkflowRequestTableRecords(workflowRequestTableRecord);
        workflowRequestInfo.setWorkflowDetailTableInfos(workflowDetailTableInfos);
        workflowRequestInfo.setWorkflowMainTableInfo(workflowMainTableInfo);
        String response  = oaWorkflowService.createFlow(workflowRequestInfo,Integer.parseInt(userId));
        return response;
    }

    // 转包对接CG005
    public String abutmentOAwithPayment(Map<String, Object> map) throws Exception {
        Map<String,Object> mapParam = subcontractCommonDao.queryPaymentParam(map.get("detailId").toString());
        JSONObject json = new JSONObject();
        //人员工号
        json.put("workcode", map.get("userCode"));
        //数据id
        json.put("id", map.get("detailId").toString());
        //流程标题
        json.put("title", map.get("purchaseOrder").toString()+"转包");
        //金蝶销售部门编码
        json.put("xiaosbm", mapParam.get("k3xiaosbm").toString());
        //金蝶采购组织编码
        json.put("caigzz", mapParam.get("caigzz").toString());
        //金蝶供应商编码
        json.put("gongys", mapParam.get("k3gys").toString());
        //订单日期
        json.put("dingdrq", DateUtil.getStringDateShort());
        //本次付款金额合计
        json.put("bencfkje", null != map.get("requestedPaymentAmount") ? map.get("requestedPaymentAmount").toString() : "");
        JSONArray dtData = new JSONArray();
        JSONObject dteDataPo = new JSONObject();
        //规格型号
        dteDataPo.put("guigxh", "0");
        //本次付款金额
        dteDataPo.put("bencfkje", null != map.get("requestedPaymentAmount") ? map.get("requestedPaymentAmount").toString() : "");
        //累计付款金额
        dteDataPo.put("leijyfkje", null != map.get("accupaid_amount") ? map.get("accupaid_amount").toString() : "0");
        //采购订单号
        dteDataPo.put("caigddh",  map.get("purchaseOrder").toString());
        //OA销售合同号
        dteDataPo.put("xiaoshth", mapParam.get("K3HT").toString());
        //交货日期
        dteDataPo.put("jiaohrq", "");
        //价税合计
        dteDataPo.put("jiashj", mapParam.get("zhuanbhtje").toString());
        //物料编码
        dteDataPo.put("wulbm", mapParam.get("wulbm").toString());
        //物料名称
        dteDataPo.put("wulmc", mapParam.get("shebmc").toString());
        //单位
        dteDataPo.put("danw", mapParam.get("danw").toString());
        //数量
        dteDataPo.put("shul", mapParam.get("shul").toString());
        //含税单价
        dteDataPo.put("hansdj", mapParam.get("zhuanbhtje").toString());
        dtData.add(dteDataPo);
        json.put("dtData", dtData);
        org.codehaus.xfire.service.Service serviceModel = new ObjectServiceFactory().create(OAwithPaymentDao.class);
        //访问的地址
        String serviceURL = "http://10.200.1.100/services/ITPROTAL";
        ClientAuthHandler wscname = new ClientAuthHandler();
        wscname.setUsername("itportal");
        wscname.setPassword("itportal");
        //为XFire获得一个代理工厂对象
        XFireProxyFactory factory = new XFireProxyFactory();
        //通过proxyFactory，使用服务模型serviceModel和服务端点URL(用来获得WSDL)
        //得到一个服务的本地代理，这个代理就是实际的客户端
        OAwithPaymentDao client = (OAwithPaymentDao) factory.create(serviceModel, serviceURL);
        XFireProxy proxy = (XFireProxy) Proxy.getInvocationHandler(client);
        Client clientOne = proxy.getClient();
        clientOne.addOutHandler(wscname);
        JSONObject returnJson2 = new JSONObject();
        returnJson2.put("methodName", "createCG005");
        JSONArray jsonArra = new JSONArray();
        jsonArra.add(json);
        returnJson2.put("param", jsonArra);
        LogUtils.info("转包生成CG005"+returnJson2.toString());
        return  client.execute(returnJson2.toString());
    }

    // 提交审核
    public static void submitAudit(String id, String formId) {
        com.kingdom.util.k3API.Submit submit = new com.kingdom.util.k3API.Submit();
        JSONObject jsonObject = submit.submit(id, formId);
        if (KingDeeContents.SUCCESS_CODE.equals(jsonObject.getString("code"))) {
            LogUtils.info("Submitted successfully:" + id);
            com.kingdom.util.k3API.Audit audit = new com.kingdom.util.k3API.Audit();
            JSONObject auditJson = audit.audit(id, formId);
            if (KingDeeContents.SUCCESS_CODE.equals(auditJson.getString("code"))) {
                LogUtils.info("Review succeeded:" + id);
            }
        }
    }

    /**
     * 解析错误信息
     * @param json
     * @return
     */
    public String analysisErrorMessage(String json){
        String res = "";
        try{
            JSONObject json1 = JSONObject.parseObject(json);
            if(json1.containsKey("Result")){
                JSONObject json2 = JSONObject.parseObject(json1.getString("Result"));
                if(json2.containsKey("ResponseStatus")){
                    JSONObject json3 = JSONObject.parseObject( json2.getString("ResponseStatus"));
                    if(!json3.getBoolean("IsSuccess")){
                        JSONArray json4  = JSONArray.parseArray(json3.getString("Errors"));
                        JSONObject json5 = JSONObject.parseObject( json4.getString(0));
                        res = json5.get("Message").toString();
                    }
                }
            }
        }catch(Exception e){
            e.printStackTrace();
        }
        return res;
    }

}
