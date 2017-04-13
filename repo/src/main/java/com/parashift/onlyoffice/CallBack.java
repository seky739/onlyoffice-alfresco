package com.parashift.onlyoffice;

import org.alfresco.model.ContentModel;
import org.alfresco.repo.policy.BehaviourFilter;
import org.alfresco.repo.version.Node2ServiceImpl;
import org.alfresco.service.cmr.lock.LockService;
import org.alfresco.service.cmr.lock.LockStatus;
import org.alfresco.service.cmr.lock.LockType;
import org.alfresco.service.cmr.repository.ContentService;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.NodeRef;
import org.alfresco.service.cmr.repository.NodeService;
import org.alfresco.service.namespace.QName;
import org.alfresco.web.bean.repository.Node;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.extensions.webscripts.AbstractWebScript;
import org.springframework.extensions.webscripts.WebScriptRequest;
import org.springframework.extensions.webscripts.WebScriptResponse;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.io.Writer;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Map;

/**
 * Created by cetra on 20/10/15.
 */
@Component(value = "webscript.onlyoffice.callback.post")
public class CallBack extends AbstractWebScript {

    @Autowired
    LockService lockService;

    @Resource(name = "policyBehaviourFilter")
    BehaviourFilter behaviourFilter;

    @Autowired
    ContentService contentService;

    private Logger logger = LoggerFactory.getLogger(this.getClass());

    @Override
    public void execute(WebScriptRequest request, WebScriptResponse response) throws IOException {

        logger.debug("Received JSON Callback");
        JSONObject callBackJSon = new JSONObject(request.getContent().getContent());

        logger.debug(callBackJSon.toString(3));

        String[] keyParts = callBackJSon.getString("key").split("_");
        NodeRef nodeRef = new NodeRef("workspace://SpacesStore/" + keyParts[0]);

        //Status codes from here: https://api.onlyoffice.com/editors/editor

        switch(callBackJSon.getInt("status")) {
            case 0:
                logger.error("ONLYOFFICE has reported that no doc with the specified key can be found");
                lockService.unlock(nodeRef);
                break;
            case 1:
                if(lockService.getLockStatus(nodeRef).equals(LockStatus.NO_LOCK)) {
                    logger.debug("Document open for editing, locking document");
                    behaviourFilter.disableBehaviour(nodeRef);
                    lockService.lock(nodeRef, LockType.WRITE_LOCK);
                } else {
                    logger.debug("Document already locked, another user has entered/exited");
                }
                break;
            case 2:
                logger.debug("Document Updated, changing content");
                lockService.unlock(nodeRef);
                updateNode(nodeRef, callBackJSon.getString("url"));
                break;
            case 3:
                logger.error("ONLYOFFICE has reported that saving the document has failed");
                lockService.unlock(nodeRef);
                break;
            case 4:
                logger.debug("No document updates, unlocking node");
                lockService.unlock(nodeRef);
                break;
        }

        //Respond as per doco
        try(Writer responseWriter = response.getWriter()) {
            JSONObject responseJson = new JSONObject();
            responseJson.put("error", 0);
            responseJson.write(responseWriter);
        }

    }

    private void updateNode(NodeRef nodeRef, String url) {
        logger.debug("Retrieving URL:{}", url);

        try {
            InputStream in = new URL( url ).openStream();
            ContentWriter writer = contentService.getWriter(nodeRef, ContentModel.PROP_CONTENT, true);


            String mimt=writer.getMimetype();
            switch (mimt){
                case "application/msword" : {
                    writer.setMimetype("application/vnd.openxmlformats-officedocument.wordprocessingml.document");

                }break;
                case "application/vnd.ms-powerpoint": {
                    writer.setMimetype("application/vnd.openxmlformats-officedocument.presentationml.presentation");
                   // name.replace("ppt","pptx");
                }break;
                case "application/vnd.ms-excel": {
                    writer.setMimetype("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
                   // name.replace("xls","xlsx");
                }break;

            }

            writer.putContent(in);

        } catch (IOException e) {
            logger.error(ExceptionUtils.getFullStackTrace(e));
        }
    }
}

