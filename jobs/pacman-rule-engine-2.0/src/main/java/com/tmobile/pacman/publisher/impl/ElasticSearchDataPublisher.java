/*******************************************************************************
 * Copyright 2018 T Mobile, Inc. or its affiliates. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy
 * of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations under
 * the License.
 ******************************************************************************/
package com.tmobile.pacman.publisher.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.http.HttpHost;
import org.elasticsearch.action.bulk.BulkItemResponse;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.common.xcontent.XContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.tmobile.pacman.common.AutoFixAction;
import com.tmobile.pacman.common.PacmanSdkConstants;
import com.tmobile.pacman.dto.AutoFixTransaction;
import com.tmobile.pacman.util.CommonUtils;
import com.tmobile.pacman.util.ESUtils;

// TODO: Auto-generated Javadoc
// not using the old way , this is the new class to publish data to ES , all old code will be refactored to use this one

/**
 * The Class ElasticSearchDataPublisher.
 */
public class ElasticSearchDataPublisher {

    
    
    /** The Constant BULK_INDEX_REQUEST_TEMPLATE. */
    private static final String BULK_INDEX_REQUEST_TEMPLATE = "{ \"index\" : { \"_index\" : \"%s\", \"parent\" : \"%s\",  \"_type\" : \"%s\"} }%n";
    
    /** The Constant logger. */
    private static final Logger logger = LoggerFactory.getLogger(ElasticSearchDataPublisher.class);
    
    /** The client. */
    private RestHighLevelClient client;
    
   /**  rest client will be used to create RestHighLevelClient. */
    private RestClient restClient;
    
    /**  test mode flag *. */
    boolean testMode = false;

    /**
     * Instantiates a new elastic search data publisher.
     */
    public ElasticSearchDataPublisher() {
        restClient = RestClient.builder(new HttpHost(ESUtils.getESHost(), ESUtils.getESPort())).build();
        client = new RestHighLevelClient(restClient);
    }
    
    /**
     * Instantiates a new elastic search data publisher.
     *
     * @param testMode the test mode
     */
    public ElasticSearchDataPublisher(Boolean testMode) {
       this.testMode = testMode;
    }

    /**
     * Publish auto fix transactions.
     *
     * @param autoFixTrans the auto fix trans
     * @param ruleParam 
     * @return the int
     */
    public int publishAutoFixTransactions(List<AutoFixTransaction> autoFixTrans, Map<String, String> ruleParam) {

        if (autoFixTrans != null && autoFixTrans.size() == 0) {
            return 0;
        }

        BulkRequest bulkRequest = new BulkRequest();
        Gson gson = new Gson();
        StringBuffer bulkRequestBody = new StringBuffer();
        String response = "";
        List<Map<String, Map>> responseList = new ArrayList<>();
        String esUrl = ESUtils.getEsUrl();
        String bulkPostUrl = esUrl + AnnotationPublisher.BULK_WITH_REFRESH_TRUE;
        final String autoFixType= PacmanSdkConstants.TYPE_FOR_AUTO_FIX_RECORD+"_"+ruleParam.get(PacmanSdkConstants.TARGET_TYPE);
        for (AutoFixTransaction autoFixTransaction : autoFixTrans) {
            
            // first post auto fix as child doc of type
            if(AutoFixAction.AUTOFIX_ACTION_FIX.equals(autoFixTransaction.getAction()))
            {   
                if(!ESUtils.isValidType(ESUtils.getEsUrl(),getIndexName(ruleParam),autoFixType)){
                try {
                        ESUtils.createMappingWithParent(ESUtils.getEsUrl(),getIndexName(ruleParam),autoFixType, ruleParam.get(PacmanSdkConstants.TARGET_TYPE));
                } catch (Exception e) {
                    logger.error("uanble to create child type");
                }
            }
                try{
                    // parent child document seems to have some issue
                    bulkRequestBody.append(String.format(BULK_INDEX_REQUEST_TEMPLATE, getIndexName(ruleParam),getDocId(autoFixTransaction), autoFixType));
//                    bulkRequestBody.append(String.format(BULK_INDEX_REQUEST_TEMPLATE, getIndexName(ruleParam),
//                            "102707241671_us-east-1_jazzsocket-jazz-s3-api-doc-20181206181415084800000007", autoFixType));
                    bulkRequestBody.append(gson.toJson(autoFixTransaction));
                    bulkRequestBody.append("\n");
                    if (bulkRequestBody.toString().getBytes().length
                            / (1024 * 1024) >= PacmanSdkConstants.ES_MAX_BULK_POST_SIZE) {
                        response = CommonUtils.doHttpPost(bulkPostUrl, bulkRequestBody.toString(),new HashMap<>());
                        responseList.add(gson.fromJson(response, Map.class));
                        bulkRequestBody.setLength(0);
                    }
                }catch (Exception e) {
                    logger.error("error occured while indexing auto fix document",e);
                    autoFixTransaction.setAdditionalInfo("error occured while indexing auto fix document " + e.getMessage());
                }
            }
            // build transaction log
            IndexRequest indexRequest = new IndexRequest(
                    CommonUtils.getPropValue(PacmanSdkConstants.AUTO_FIX_TRAN_INDEX_NAME_KEY),
                    CommonUtils.getPropValue(PacmanSdkConstants.AUTO_FIX_TRAN_TYPE_NAME_KEY));
            indexRequest.source(gson.toJson(autoFixTransaction), XContentType.JSON);
            bulkRequest.add(indexRequest);
        }
        
        // post the remaining data if available
            if (bulkRequestBody.length() > 0) {
                response = CommonUtils.doHttpPost(bulkPostUrl, bulkRequestBody.toString(),new HashMap<>());
            }
            responseList.add(gson.fromJson(response, Map.class));
        
        // now post transaction log
        try {
            if(null!=client){
                   BulkResponse bulkResponse = client.bulk(bulkRequest);
                   if (bulkResponse.hasFailures()) {
                       if (!isIndexAvaialble(bulkResponse.getItems())) {
                           logger.info("index not found to write the transaction logs, creating one");
                            // version 5.6 does not support index creation via API,
                            // hence executing a post
                            try {
                                CommonUtils
                                    .doHttpPut(
                                        ESUtils.getEsUrl() + "/"
                                                + CommonUtils
                                                        .getPropValue(PacmanSdkConstants.AUTO_FIX_TRAN_INDEX_NAME_KEY),
                                        "");
                                publishAutoFixTransactions(autoFixTrans,ruleParam); // index should be created now
                            } catch (Exception e) {

                        logger.error("error creating index", e);
                        }
                       }
                   }
            }
        } catch (IOException e) {
            logger.error("error posting auto fix transaction log", e);
            return -1;
        }
        return 0;
    }

    /**
     * @param autoFixTransaction
     * @return
     */
    private String getDocId(AutoFixTransaction autoFixTransaction) {
        return autoFixTransaction.getAccountId()+ "_" + autoFixTransaction.getRegion() + "_" + autoFixTransaction.getResourceId();
    }

    /**
     * @param ruleParam
     * @return
     */
    private String getIndexName(Map<String, String> ruleParam) {
        
        return ruleParam.get(PacmanSdkConstants.DATA_SOURCE_KEY).replace("_all", "") + "_"
                + ruleParam.get(PacmanSdkConstants.TARGET_TYPE);
    }

    /**
     * @param autoFixTransaction 
     * @return
     */
    private Map buildDoc(AutoFixTransaction autoFixTransaction) {
        Map<String,String> doc = new HashMap<>();
        doc.put(PacmanSdkConstants.RULE_ID,autoFixTransaction.getRuleId());
        doc.put(PacmanSdkConstants.TRANSACTION_ID,autoFixTransaction.getTransactionId());
        doc.put(PacmanSdkConstants.TRANSACTION_TIME,autoFixTransaction.getTransationTime());
        doc.put(PacmanSdkConstants.EXECUTION_ID,autoFixTransaction.getExecutionId());
        doc.put("parent", "102707241671_us-east-1_jazzsocket-jazz-s3-api-doc-20181206181415084800000007");
        return doc;
    }

    /**
     * Checks if is index avaialble.
     *
     * @param bulkItemResponses the bulk item responses
     * @return the boolean
     */
    private Boolean isIndexAvaialble(BulkItemResponse[] bulkItemResponses) {
        // System.out.println(bulkItemResponses[0].getFailureMessage());
        // System.out.println(bulkItemResponses[0].getFailure().getMessage());
        return null == Arrays.stream(bulkItemResponses)
                .filter(x -> x.getFailure().getCause().getMessage().contains("no such index")).findAny().orElse(null);
    }
    
    
    /**
     * Close.
     */
    public void close(){
        if(null!=restClient)
            try {
                restClient.close();
            } catch (IOException e) {
                logger.error("error closing rest client" ,e);
            }
        
        client = null;
    }
    
    public static void main(String[] args) {
        List<AutoFixTransaction> autoFixTrans = new ArrayList<AutoFixTransaction>();
        AutoFixTransaction autoFixTransaction = new AutoFixTransaction();
        autoFixTransaction.setAction(AutoFixAction.AUTOFIX_ACTION_FIX);
        autoFixTransaction.setRuleId("PacMan_S3GlobalAccess_version-1_S3BucketShouldnotpubliclyaccessble_s3");
        autoFixTransaction.setResourceId("jazzsocket-jazz-s3-api-doc-20181206181415084800000007");
        autoFixTransaction.setTransationTime("2018-12-12T10:36:32.387Z");
        autoFixTransaction.setExecutionId("193800a5-c759-4143-b364-25ad94446378");
        autoFixTransaction.setTransactionId("736c0082f0cb2e68321a1b82e3bfa766");
        autoFixTransaction.setType("s3");
        autoFixTransaction.setAccountId("102707241671");
        autoFixTransaction.setRegion("us-east-1");
        autoFixTransaction.setAdditionalInfo("additional info 1");
        autoFixTransaction.setIssueId("736c0082f0cb2e68321a1b82e3bfa766");
        autoFixTrans.add(autoFixTransaction);
        Map<String, String> ruleParam  = CommonUtils.createParamMap(args[0]);
        ElasticSearchDataPublisher edp = new ElasticSearchDataPublisher();
        edp.publishAutoFixTransactions(autoFixTrans, ruleParam);
        edp.close();
        
    }

}
