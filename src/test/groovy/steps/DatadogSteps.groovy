package steps

import io.restassured.response.Response
import org.hamcrest.Matchers
import org.yaml.snakeyaml.Yaml

import java.util.stream.Collectors

import static io.restassured.RestAssured.given

class DatadogSteps {
    Yaml yaml = new Yaml()
    def configFile = new File("./src/test/resources/testenv.yaml")
    def config = yaml.load(configFile.text)
    def from
    def to

    DatadogSteps(from, to) {
        this.from = from
        this.to = to
    }


    static def aggregateLogs(dd_url, api_key, application_key, query) {
        return given()
                .relaxedHTTPSValidation()
                .header("Cache-Control", "no-cache")
                .header("Content-Type", "application/json")
                .header("DD-API-KEY", api_key)
                .header("DD-APPLICATION-KEY", application_key)
                .body(query)
                .when()
                .post(dd_url + "/api/v2/logs/analytics/aggregate")
                .then()
                .extract().response()
    }

    def getMapOfCountLogAggregation(dd_url, api_key, application_key, query, groupby_facet){
        def cursor = ""
        Map<String, Integer> counts = new HashMap<>()
        // Since results are paginated, go through all pages.
        do {
            def json_query = getCountQuery(query, [groupby_facet], cursor)
            Response response = aggregateLogs(dd_url, api_key, application_key, json_query)
            response.then().log().ifValidationFails().statusCode(200).body("meta.status", Matchers.equalTo("done"))

            response.jsonPath().getList("data.buckets").forEach({ it ->
                counts.put(it["by"][groupby_facet.toString()].toString(), it["computes"]["c0"] as Integer)
            })
            cursor = response.jsonPath().get("meta.page.after")
        } while (cursor)
        return counts
    }

    def getMapOfMetricCardinalityLogAggregation(dd_url, api_key, application_key, query, metric, groupby_facet){
        def cursor = ""
        Map<String, Integer> counts = new HashMap<>()
        // Since results are paginated, go through all pages.
        do {
            def json_query = getMetricCardinalityQuery(query, metric, [groupby_facet], cursor)
            Response response = aggregateLogs(dd_url, api_key, application_key, json_query)
            response.then().log().ifValidationFails().statusCode(200).body("meta.status", Matchers.equalTo("done"))

            response.jsonPath().getList("data.buckets").forEach({ it ->
                counts.put(it["by"][groupby_facet.toString()].toString(), it["computes"]["c0"] as Integer)
            })
            cursor = response.jsonPath().get("meta.page.after")
        } while (cursor)
        return counts
    }

    def topVulnerableArtifacts(dd_url, api_key, application_key, by) {
        def cursor = ""
        Map<String, Integer> counts = new HashMap<>()
        do {
            def json_query = getCountQuery("@log_source:jfrog.rt.artifactory.access @action_response:\\\"ACCEPTED DOWNLOAD\\\"", ["@impacted_artifacts", by], cursor)
            Response response = aggregateLogs(dd_url, api_key, application_key, json_query)
            response.then().log().ifValidationFails().statusCode(200).body("meta.status", Matchers.equalTo("done"))

            response.jsonPath().getList("data.buckets").forEach({ it ->
                counts.put(it["by"]["@impacted_artifacts"].toString(), counts.getOrDefault(it["by"]["@impacted_artifacts"].toString(), 0) + 1)
            })
            cursor = response.jsonPath().get("meta.page.after")
        } while (cursor)
        return counts
    }

    static Map<String, Integer> renameMapKeysForWatches(Map<String, Integer> map) {
        return map.collect().stream().collect(
                Collectors.toMap(
                        {Map.Entry<String, Integer> it -> it.key.contains("security") ? "security" : it.key.substring(it.key.lastIndexOf("_")+1)},
                        {Map.Entry<String, Integer> it -> it.value},
                        Integer::sum
                )
        )
    }

    static Map<String, Integer> renameMapKeysForPolicies(Map<String, Integer> map) {
        return map.collect().stream().collect(
                Collectors.toMap(
                        {Map.Entry<String, Integer> it ->
                            it.key.contains("security") ? "security" : (
                                    it.key.contains("License") && it.key.contains("Rule")
                                    ? it.key.substring("License".length(), it.key.lastIndexOf("Rule"))
                                    : it.key
                            )
                        },
                        {Map.Entry<String, Integer> it -> it.value},
                        Integer::sum
                )
        )
    }

    static Map<String, Integer> extractArtifactNamesToMap(Map<String, Integer> map) {
        return map.collect().stream().collect(
                Collectors.toMap(
                        {Map.Entry<String, Integer> it ->
                                it.key.substring(it.key.lastIndexOf("/") + 1)
                        },
                        {Map.Entry<String, Integer> it -> it.value},
                        Integer::sum
                )
        )
    }



    /**
     * ===========
     * | Queries |
     * ===========
     * These are named query_<test name> and used in DatadogTest.groovy
     * */

    def getCountQuery(query, groupby_facets = [], cursor = "") {
        def groupby = ""
        for (facet in groupby_facets) {
            groupby += "{ \"facet\":\"${facet}\" },"
        }
        if (groupby) {
            groupby = groupby.substring(0, groupby.length() - 1)
        }

        return "{\n" +
                "    \"compute\": [\n" +
                "        {\n" +
                "            \"aggregation\": \"count\"\n" +
                "        }\n" +
                "    ],\n" +
                "    \"filter\": {\n" +
                "        \"from\": \"${from}\",\n" +
                "        \"indexes\": [\n" +
                "            \"*\"\n" +
                "        ],\n" +
                "        \"query\": \"${query}\",\n" +
                "        \"to\": \"${to}\"\n" +
                "    },\n" +
                "    \"group_by\": [\n" +
                (groupby ?: "") +
                "    ],\n" +
                "    \"page\": {\n" +
                (cursor ? "  \"cursor\": \"${cursor}\"\n" : "") +
                "    }" +
                "}"
    }

    def getMetricCardinalityQuery(query, metric, groupby_facets = [], cursor = "") {
        def groupby = ""
        for (facet in groupby_facets) {
            groupby += "{ \"facet\":\"${facet}\" },"
        }
        if (groupby) {
            groupby = groupby.substring(0, groupby.length() - 1)
        }

        return "{\n" +
                "    \"compute\": [\n" +
                "        {\n" +
                "            \"metric\": \"${metric}\",\n" +
                "            \"aggregation\": \"cardinality\"\n" +
                "        }\n" +
                "    ],\n" +
                "    \"filter\": {\n" +
                "        \"from\": \"${from}\",\n" +
                "        \"indexes\": [\n" +
                "            \"*\"\n" +
                "        ],\n" +
                "        \"query\": \"${query}\",\n" +
                "        \"to\": \"${to}\"\n" +
                "    },\n" +
                "    \"group_by\": [\n" +
                (groupby ?: "") +
                "    ],\n" +
                "    \"page\": {\n" +
                (cursor ? "  \"cursor\": \"${cursor}\"\n" : "") +
                "    }" +
                "}"
    }

}
