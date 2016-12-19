/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.rankeval;

import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.HandledTransportAction;
import org.elasticsearch.client.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.query.QueryParseContext;
import org.elasticsearch.script.CompiledScript;
import org.elasticsearch.script.Script;
import org.elasticsearch.script.ScriptContext;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.SearchRequestParsers;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Instances of this class execute a collection of search intents (read: user supplied query parameters) against a set of
 * possible search requests (read: search specifications, expressed as query/search request templates) and compares the result
 * against a set of annotated documents per search intent.
 *
 * If any documents are returned that haven't been annotated the document id of those is returned per search intent.
 *
 * The resulting search quality is computed in terms of precision at n and returned for each search specification for the full
 * set of search intents as averaged precision at n.
 * */
public class TransportRankEvalAction extends HandledTransportAction<RankEvalRequest, RankEvalResponse> {
    private Client client;
    private ScriptService scriptService;
    private SearchRequestParsers searchRequestParsers;
    
    @Inject
    public TransportRankEvalAction(Settings settings, ThreadPool threadPool, ActionFilters actionFilters,
            IndexNameExpressionResolver indexNameExpressionResolver, Client client, TransportService transportService,
            SearchRequestParsers searchRequestParsers, ScriptService scriptService) {
        super(settings, RankEvalAction.NAME, threadPool, transportService, actionFilters, indexNameExpressionResolver,
                RankEvalRequest::new);
        this.searchRequestParsers = searchRequestParsers;
        this.scriptService = scriptService;
        this.client = client;
    }

    @Override
    protected void doExecute(RankEvalRequest request, ActionListener<RankEvalResponse> listener) {
        RankEvalSpec qualityTask = request.getRankEvalSpec();

        Collection<RatedRequest> specifications = qualityTask.getRatedRequests();
        AtomicInteger responseCounter = new AtomicInteger(specifications.size());
        Map<String, EvalQueryQuality> partialResults = new ConcurrentHashMap<>(specifications.size());
        Map<String, Exception> errors = new ConcurrentHashMap<>(specifications.size());

        Map<String, CompiledScript> scriptsWithoutParams = new HashMap<>();
        for (Entry<String, Script> entry : qualityTask.getTemplates().entrySet()) {
             scriptsWithoutParams.put(
                     entry.getKey(), 
                     scriptService.compile(entry.getValue(), ScriptContext.Standard.SEARCH, new HashMap<>()));
        }
        for (RatedRequest ratedRequest : specifications) {
            final RankEvalActionListener searchListener = new RankEvalActionListener(listener, qualityTask.getMetric(), ratedRequest,
                    partialResults, errors, responseCounter);
            SearchSourceBuilder ratedSearchSource = ratedRequest.getTestRequest();
            if (ratedSearchSource == null) {
                Map<String, Object> params = ratedRequest.getParams();
                String templateId = ratedRequest.getTemplateId();
                CompiledScript compiled = scriptsWithoutParams.get(templateId);
                String resolvedRequest = ((BytesReference) (scriptService.executable(compiled, params).run())).utf8ToString();
                try (XContentParser subParser = XContentFactory.xContent(resolvedRequest).createParser(resolvedRequest)) {
                    QueryParseContext parseContext = new QueryParseContext(searchRequestParsers.queryParsers, subParser, parseFieldMatcher);
                    ratedSearchSource = SearchSourceBuilder.fromXContent(parseContext, searchRequestParsers.aggParsers,
                            searchRequestParsers.suggesters, searchRequestParsers.searchExtParsers);
                } catch (IOException e) {
                    listener.onFailure(e);
                }
            }
            List<String> summaryFields = ratedRequest.getSummaryFields();
            if (summaryFields.isEmpty()) {
                ratedSearchSource.fetchSource(false);
            } else {
                ratedSearchSource.fetchSource(summaryFields.toArray(new String[summaryFields.size()]), new String[0]);
            }

            String[] indices = new String[ratedRequest.getIndices().size()];
            indices = ratedRequest.getIndices().toArray(indices);
            SearchRequest templatedRequest = new SearchRequest(indices, ratedSearchSource);

            String[] types = new String[ratedRequest.getTypes().size()];
            types = ratedRequest.getTypes().toArray(types);
            templatedRequest.types(types);

            client.search(templatedRequest, searchListener);
        }
    }

    public static class RankEvalActionListener implements ActionListener<SearchResponse> {

        private ActionListener<RankEvalResponse> listener;
        private RatedRequest specification;
        private Map<String, EvalQueryQuality> requestDetails;
        private Map<String, Exception> errors;
        private RankedListQualityMetric metric;
        private AtomicInteger responseCounter;

        public RankEvalActionListener(ActionListener<RankEvalResponse> listener, RankedListQualityMetric metric, RatedRequest specification,
                Map<String, EvalQueryQuality> details, Map<String, Exception> errors, AtomicInteger responseCounter) {
            this.listener = listener;
            this.metric = metric;
            this.errors = errors;
            this.specification = specification;
            this.requestDetails = details;
            this.responseCounter = responseCounter;
        }

        @Override
        public void onResponse(SearchResponse searchResponse) {
            SearchHit[] hits = searchResponse.getHits().getHits();
            EvalQueryQuality queryQuality = metric.evaluate(specification.getId(), hits, specification.getRatedDocs());
            requestDetails.put(specification.getId(), queryQuality);
            handleResponse();
        }

        @Override
        public void onFailure(Exception exception) {
            errors.put(specification.getId(), exception);
            handleResponse();
        }

        private void handleResponse() {
            if (responseCounter.decrementAndGet() == 0) {
                // TODO add other statistics like micro/macro avg?
                listener.onResponse(new RankEvalResponse(metric.combine(requestDetails.values()), requestDetails, errors));
            }
        }
    }
}