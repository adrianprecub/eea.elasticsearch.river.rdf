package  org.elasticsearch.app;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.nio.client.HttpAsyncClientBuilder;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;

import org.elasticsearch.action.search.*;
import org.elasticsearch.app.logging.ESLogger;
import org.elasticsearch.app.logging.Loggers;
import org.elasticsearch.app.river.River;
import org.elasticsearch.app.river.RiverName;
import org.elasticsearch.app.river.RiverSettings;
import org.elasticsearch.client.*;

import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.support.XContentMapValues;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.search.Scroll;
import org.elasticsearch.search.SearchHit;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Indexer {
    private final static String USER = "user_rw";
    private final static String PASS = "rw_pass";
    private final static String HOST = "localhost";
    private final static int PORT = 9200;

    private String RIVER_INDEX = "eeariver";

    public String getRIVER_INDEX() {
        return RIVER_INDEX;
    }

    private boolean MULTITHREADING_ACTIVE = false;
    private int THREADS = 1;
    public String loglevel;

    private static final ESLogger logger = Loggers.getLogger(Indexer.class);

    private ArrayList<River> rivers = new ArrayList<>();

    public Map<String, String> envMap;

    private static final CredentialsProvider credentialsProvider = new BasicCredentialsProvider();

    public RestHighLevelClient client;

    private static ExecutorService executorService;

    public static void main(String[] args) throws IOException {

        logger.info("Starting application...");

        Indexer indexer = new Indexer();
        logger.setLevel(indexer.loglevel);

        logger.info("Username:" + indexer.envMap.get("elastic_user"));
        logger.info("Password: " + indexer.envMap.get("elastic_pass"));
        logger.info("HOST: " + indexer.envMap.get("elastic_host"));
        logger.info("PORT: " + indexer.envMap.get("elastic_port"));
        logger.info("RIVER INDEX: " + indexer.RIVER_INDEX);
        logger.info("MULTITHREADING_ACTIVE: " + indexer.MULTITHREADING_ACTIVE);
        logger.info("THREADS: " + indexer.THREADS);
        logger.info("LOG_LEVEL: " + indexer.envMap.get("LOG_LEVEL") );
        logger.info("DOCUMENT BULK: ", Integer.toString(EEASettings.DEFAULT_BULK_REQ) );

        if(indexer.rivers.size() == 0){
            logger.info("No rivers detected");
            logger.info("No rivers added in " + indexer.RIVER_INDEX + " index.Stopping...");
            indexer.close();
        }

        //TODO: loop for all rivers
        if(indexer.MULTITHREADING_ACTIVE){
        /*Indexer.executorService = EsExecutors.newAutoQueueFixed("threadPool", 1, 5, 5, 26,2,
                TimeValue.timeValueHours(10), EsExecutors.daemonThreadFactory("esapp"), new ThreadContext(Builder.EMPTY_SETTINGS));*/
            Indexer.executorService = Executors.newFixedThreadPool(indexer.THREADS);
            //Indexer.executorService = Executors.newWorkStealingPool(4);

        } else {
            Indexer.executorService = Executors.newSingleThreadExecutor();
        }

        for(River river : indexer.rivers){
            Harvester h = new Harvester();

            h.client(indexer.client).riverName(river.riverName())
                    .riverIndex(indexer.RIVER_INDEX)
                    .indexer(indexer);
            indexer.addHarvesterSettings(h, river.getRiverSettings());

            Indexer.executorService.submit(h);
            logger.info("Created thread for river: {}", river.riverName());
        }

        Indexer.executorService.shutdown();

        logger.info("All tasks submitted.");
        try {
            Indexer.executorService.awaitTermination(1, TimeUnit.DAYS);

            try {
                DeleteIndexRequest request = new DeleteIndexRequest(indexer.RIVER_INDEX);
                indexer.client.indices().delete(request);
                logger.info("Deleting river index!!!");

            } catch (ElasticsearchException exception) {
                if (exception.status() == RestStatus.NOT_FOUND) {
                    logger.error("River index not found");
                    logger.info("Tasks interrupted by missing river index.");
                    indexer.close();
                }
            }

        } catch (InterruptedException ignored) {
            logger.info("Tasks interrupted.");
        }
        logger.info("All tasks completed.");
        indexer.close();

    }

    public Indexer() {
        Map<String, String> env = System.getenv();
        this.envMap = env;

        String host = (env.get("elastic_host") != null) ? env.get("elastic_host") : HOST;

        int port = (env.get("elastic_port") != null) ? Integer.parseInt(env.get("elastic_port")) : PORT;
        String user = (env.get("elastic_user") != null) ? env.get("elastic_user") : USER;
        String pass = (env.get("elastic_pass") != null) ? env.get("elastic_pass") : PASS;
        this.RIVER_INDEX = ( env.get("river_index") != null) ? env.get("river_index") : this.RIVER_INDEX;
        this.MULTITHREADING_ACTIVE  = (env.get("indexer_multithreading") != null) ?
                Boolean.parseBoolean(env.get("indexer_multithreading")) : this.MULTITHREADING_ACTIVE;
        this.THREADS = (env.get("threads") != null) ? Integer.parseInt(env.get("threads")) : this.THREADS;
        this.loglevel = ( env.get("LOG_LEVEL") != null) ? env.get("LOG_LEVEL") : "info";

        credentialsProvider.setCredentials(AuthScope.ANY,
                new UsernamePasswordCredentials(user , pass));

        client = new RestHighLevelClient(
            RestClient.builder(
                    new HttpHost( host , port, "http")
            ).setHttpClientConfigCallback(new RestClientBuilder.HttpClientConfigCallback() {
                @Override
                public HttpAsyncClientBuilder customizeHttpClient(HttpAsyncClientBuilder httpClientBuilder) {
                    return httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }
            })
            .setFailureListener(new RestClient.FailureListener(){
                @Override
                public void onFailure(HttpHost host) {
                    super.onFailure(host);
                    logger.error("Connection failure: [{}]", host);
                }
            })
        );

        getAllRivers();
    }

    public void getRivers(){
        this.getAllRivers();

    }

    private void getAllRivers() {
        this.rivers.clear();
        ArrayList< SearchHit > searchHitsA = new ArrayList<>();

        final Scroll scroll = new Scroll(TimeValue.timeValueMinutes(1L));
        SearchRequest searchRequest = new SearchRequest(this.RIVER_INDEX);
        searchRequest.scroll(scroll);

        SearchResponse searchResponse = null;
        try {
            searchResponse = client.search(searchRequest);
            logger.info("River index {} found", this.RIVER_INDEX);
            String scrollId = searchResponse.getScrollId();
            SearchHit[] searchHits = searchResponse.getHits().getHits();

            //process the hits
            searchHitsA.addAll(Arrays.asList(searchHits));

            while (searchHits != null && searchHits.length > 0) {
                SearchScrollRequest scrollRequest = new SearchScrollRequest(scrollId);
                scrollRequest.scroll(scroll);
                searchResponse = client.searchScroll(scrollRequest);
                scrollId = searchResponse.getScrollId();
                searchHits = searchResponse.getHits().getHits();

                //process the hits
                searchHitsA.addAll(Arrays.asList(searchHits));
            }

            ClearScrollRequest clearScrollRequest = new ClearScrollRequest();
            clearScrollRequest.addScrollId(scrollId);
            ClearScrollResponse clearScrollResponse = client.clearScroll(clearScrollRequest);
            boolean succeeded = clearScrollResponse.isSucceeded();
        } catch (IOException e) {
            logger.info("River index " + this.RIVER_INDEX + " not found");
            System.exit(0);
        } catch (ElasticsearchStatusException ex){
            logger.info("River index " + this.RIVER_INDEX + " not found");
            System.exit(0);
        }

        for (SearchHit  sh: searchHitsA ){
            Map<String, Object> source = sh.getSourceAsMap();
            //logger.debug("{}", source.containsKey("eeaRDF"));

            if(source.containsKey("eeaRDF")){
                RiverSettings riverSettings = new RiverSettings(source);
                RiverName riverName = new RiverName("eeaRDF", sh.getId());
                River river = new River()
                        .setRiverName( riverName.name() )
                        .setRiverSettings( riverSettings );
                rivers.add(river);
                continue;
            }

            if ( !((Map)source.get("syncReq")).containsKey("eeaRDF")) {
                logger.error( "not has river settings: " + sh.getId() );
                throw new IllegalArgumentException(
                        "There are no eeaRDF settings in the river settings");

            } else {
                RiverSettings riverSettings = new RiverSettings(source);
                RiverName riverName = new RiverName("eeaRDF", sh.getId());
                River river = new River()
                        .setRiverName( riverName.name() )
                        .setRiverSettings( riverSettings );
                rivers.add(river);
            }

        }
    }

    private void addHarvesterSettings(Harvester harv, RiverSettings settings) {
        if(settings.getSettings().containsKey("eeaRDF")){

        } else if ( ! (((HashMap)settings.getSettings().get("syncReq")).containsKey("eeaRDF")) ) {
            logger.error("There are no syncReq settings in the river settings");
            throw new IllegalArgumentException(
                    "There are no eeaRDF settings in the river settings");
        }

        Map<String, Object> rdfSettings = extractSettings(settings, "eeaRDF");

        harv.rdfIndexType(XContentMapValues.nodeStringValue(
                rdfSettings.get("indexType"), "full"))
                .rdfStartTime(XContentMapValues.nodeStringValue(
                        rdfSettings.get("startTime"),""))
                .rdfUris(XContentMapValues.nodeStringValue(
                        rdfSettings.get("uris"), "[]"))
                .rdfEndpoint(XContentMapValues.nodeStringValue(
                        rdfSettings.get("endpoint"),
                        EEASettings.DEFAULT_ENDPOINT))
                .rdfClusterId(XContentMapValues.nodeStringValue(
                        rdfSettings.get("cluster_id"),
                        EEASettings.DEFAULT_CLUSTER_ID))
                .rdfQueryType(XContentMapValues.nodeStringValue(
                        rdfSettings.get("queryType"),
                        EEASettings.DEFAULT_QUERYTYPE))
                .rdfListType(XContentMapValues.nodeStringValue(
                        rdfSettings.get("listtype"),
                        EEASettings.DEFAULT_LIST_TYPE))
                .rdfAddLanguage(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("addLanguage"),
                        EEASettings.DEFAULT_ADD_LANGUAGE))
                .rdfAddCounting(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("addCounting"),
                        EEASettings.DEFAULT_ADD_COUNTING))
                .rdfLanguage(XContentMapValues.nodeStringValue(
                        rdfSettings.get("language"),
                        EEASettings.DEFAULT_LANGUAGE))
                .rdfAddUriForResource(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("includeResourceURI"),
                        EEASettings.DEFAULT_ADD_URI))
                .rdfURIDescription(XContentMapValues.nodeStringValue(
                        rdfSettings.get("uriDescription"),
                        EEASettings.DEFAULT_URI_DESCRIPTION))
                .rdfSyncConditions(XContentMapValues.nodeStringValue(
                        rdfSettings.get("syncConditions"),
                        EEASettings.DEFAULT_SYNC_COND))
                .rdfGraphSyncConditions(XContentMapValues.nodeStringValue(
                        rdfSettings.get("graphSyncConditions"), ""))
                .rdfSyncTimeProp(XContentMapValues.nodeStringValue(
                        rdfSettings.get("syncTimeProp"),
                        EEASettings.DEFAULT_SYNC_TIME_PROP))
                .rdfSyncOldData(XContentMapValues.nodeBooleanValue(
                        rdfSettings.get("syncOldData"),
                        EEASettings.DEFAULT_SYNC_OLD_DATA));

        if (rdfSettings.containsKey("proplist")) {
            harv.rdfPropList(getStrListFromSettings(rdfSettings, "proplist"));
        }
        if(rdfSettings.containsKey("query")) {
            harv.rdfQuery(getStrListFromSettings(rdfSettings, "query"));
        } else {
            harv.rdfQuery(EEASettings.DEFAULT_QUERIES);
        }

        if(rdfSettings.containsKey("normProp")) {
            harv.rdfNormalizationProp(getStrObjMapFromSettings(rdfSettings, "normProp"));
        }
        if(rdfSettings.containsKey("normMissing")) {
            harv.rdfNormalizationMissing(getStrObjMapFromSettings(rdfSettings, "normMissing"));
        }
        if(rdfSettings.containsKey("normObj")) {
            harv.rdfNormalizationObj(getStrStrMapFromSettings(rdfSettings, "normObj"));
        }
        if(rdfSettings.containsKey("blackMap")) {
            harv.rdfBlackMap(getStrObjMapFromSettings(rdfSettings, "blackMap"));
        }
        if(rdfSettings.containsKey("whiteMap")) {
            harv.rdfWhiteMap(getStrObjMapFromSettings(rdfSettings, "whiteMap"));
        }
        //TODO : change to index
        if(settings.getSettings().containsKey("index")){
            Map<String, Object> indexSettings = extractSettings(settings, "index");
            harv.index(XContentMapValues.nodeStringValue(
                    indexSettings.get("index"),
                    EEASettings.DEFAULT_INDEX_NAME)
            )
                    .type(XContentMapValues.nodeStringValue(
                            indexSettings.get("type"),
                            EEASettings.DEFAULT_TYPE_NAME)

            )
                    .statusIndex(XContentMapValues.nodeStringValue(indexSettings.get("statusIndex"),"status")
            );
        }
        else {
            //TODO: don't know if is correct
            if( settings.getSettings().containsKey("syncReq")){
                harv.index(  ((HashMap)((HashMap)settings.getSettings().get("syncReq")).get("index")).get("index").toString() );
                harv.type( ((HashMap)((HashMap)settings.getSettings().get("syncReq")).get("index")).get("type").toString() );
                harv.statusIndex(  ((HashMap)((HashMap)settings.getSettings().get("syncReq")).get("index")).get("statusIndex").toString() );
            } else {
                harv.index(EEASettings.DEFAULT_INDEX_NAME);
                harv.type( "river" );
                harv.statusIndex("status");
            }

        }

    }

    /** Type casting accessors for river settings **/
    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractSettings(RiverSettings settings,
                                                       String key) {
        if(settings.getSettings().containsKey("eeaRDF")){
            return (Map<String, Object>) ( (Map<String, Object>)settings.getSettings()).get(key);
        } else {
            return (Map<String, Object>) ( (Map<String, Object>)settings.getSettings().get("syncReq")).get(key);
        }

    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> getStrStrMapFromSettings(Map<String, Object> settings,
                                                                String key) {
        return (Map<String, String>)settings.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> getStrObjMapFromSettings(Map<String, Object> settings,
                                                                String key) {
        return (Map<String, Object>)settings.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStrListFromSettings(Map<String, Object> settings,
                                                       String key) {
        return (List<String>)settings.get(key);
    }

    public void start() {

    }

    public void close() {
        System.exit(0);
    }

    public void closeHarvester(Harvester that) {
        logger.info("Closing thread");
    }

}