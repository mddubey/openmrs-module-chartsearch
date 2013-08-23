package org.apache.solr.handler.dataimport;

import static org.apache.solr.handler.dataimport.DataImporter.IMPORT_CMD;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.params.UpdateParams;
import org.apache.solr.common.util.ContentStream;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.handler.dataimport.custom.PatientInfoHolder;
import org.apache.solr.request.SolrQueryRequest;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.update.processor.UpdateRequestProcessor;
import org.apache.solr.update.processor.UpdateRequestProcessorChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChartSearchIndexUpdater {
	
	private static final Logger log = LoggerFactory.getLogger(ChartSearchIndexUpdater.class);
	
	private final DataImporter importer;
	
	private final NamedList initArgs;
	
	private final PatientInfoHolder patientInfoHolder;
	
	private boolean debugEnabled = true;
	
	public ChartSearchIndexUpdater(DataImporter dataImporter, NamedList initArgs, PatientInfoHolder patientInfoHolder) {
		this.importer = dataImporter;
		this.initArgs = initArgs;
		this.patientInfoHolder = patientInfoHolder;
	}	
	
	/*	Simulate RequestHandlerBase handleRequestBody */
	public void handleRequest(SolrQueryRequest req, SolrQueryResponse rsp) {
		SolrParams params = req.getParams();
		Integer patientId = params.getInt("personId");
		
		log.info("Daemon #{} taked from queue patient #{}", Thread.currentThread().getName(), patientId);
		
		// TODO: figure out why just the first one is OK...
		ContentStream contentStream = null;
		Iterable<ContentStream> streams = req.getContentStreams();
		if (streams != null) {
			for (ContentStream stream : streams) {
				contentStream = stream;
				break;
			}
		}
		Map<String, Object> paramsMap = getParamsMap(params);
		
		if (DataImporter.IMPORT_CMD.equals(paramsMap.get("command"))) {
			Date lastIndexTime = patientInfoHolder.getLastIndexTime(patientId);
			if (lastIndexTime != null) {
				log.info("Putting last index time [{}] into params map", lastIndexTime);
				
				//Do delta import
				paramsMap.put("lastIndexTime", lastIndexTime);
				paramsMap.put("command", DataImporter.DELTA_IMPORT_CMD);
			}
			
		}
		
		RequestInfo requestParams = new RequestInfo(paramsMap, contentStream);
		
		NamedList defaultParams = (NamedList) initArgs.get("defaults");
		
		String command = requestParams.getCommand();
		
		String message = StringUtils.EMPTY;
		
		if (DataImporter.FULL_IMPORT_CMD.equals(command) || DataImporter.DELTA_IMPORT_CMD.equals(command)
		        || IMPORT_CMD.equals(command)) {
			
			try {
				importer.maybeReloadConfiguration(requestParams, defaultParams);
			}
			catch (IOException e) {
				// TODO Auto-generated catch block
				log.error("Error generated", e);
			}
			UpdateRequestProcessorChain processorChain = req.getCore().getUpdateProcessingChain(
			    params.get(UpdateParams.UPDATE_CHAIN));
			UpdateRequestProcessor processor = processorChain.createProcessor(req, rsp);
			SolrResourceLoader loader = req.getCore().getResourceLoader();
			
			SolrWriter sw = getSolrWriter(processor, req);
			try {
				if (requestParams.isDebug() && !debugEnabled) {
					message = DataImporter.MSG.DEBUG_NOT_ENABLED;
				} else {
					importer.runCmd(requestParams, sw);
					patientInfoHolder.setLastIndexTime(patientId);
					
					if (requestParams.isDebug()) {
						rsp.add("mode", "debug");
						rsp.add("documents", requestParams.getDebugInfo().debugDocuments);
						if (requestParams.getDebugInfo().debugVerboseOutput != null) {
							rsp.add("verbose-output", requestParams.getDebugInfo().debugVerboseOutput);
						}
					}
				}
			}
			finally {
				sw.close();
			}
			log.info("Import finished in the daemon {}", Thread.currentThread().getName());
		} else if (DataImporter.RELOAD_CONF_CMD.equals(command)) {
			try {
				if (importer.maybeReloadConfiguration(requestParams, defaultParams)) {
					message = DataImporter.MSG.CONFIG_RELOADED;
				} else {
					message = DataImporter.MSG.CONFIG_NOT_RELOADED;
				}
			}
			catch (IOException e) {
				// TODO Auto-generated catch block
				log.error("Error generated", e);
			}
		}
		
		// TODO Do something visible for user with response
		rsp.add("importResponse", message);
		rsp.add("statusMessages", importer.getStatusMessages());
		
	}
	
	private SolrWriter getSolrWriter(final UpdateRequestProcessor processor,  SolrQueryRequest req) {
		
		return new SolrWriter(processor, req) {
			
			@Override
			public boolean upload(SolrInputDocument document) {
				try {
					return super.upload(document);
				}
				catch (RuntimeException e) {
					log.error("Exception while adding: " + document, e);
					return false;
				}
			}
		};
	}
	
	private Map<String, Object> getParamsMap(SolrParams params) {
		Iterator<String> names = params.getParameterNamesIterator();
		Map<String, Object> result = new HashMap<String, Object>();
		while (names.hasNext()) {
			String s = names.next();
			String[] val = params.getParams(s);
			if (val == null || val.length < 1)
				continue;
			if (val.length == 1)
				result.put(s, val[0]);
			else
				result.put(s, Arrays.asList(val));
		}
		return result;
	}
}
