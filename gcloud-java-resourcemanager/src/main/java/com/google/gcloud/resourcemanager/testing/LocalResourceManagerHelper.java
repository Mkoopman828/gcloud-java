package com.google.gcloud.resourcemanager.testing;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static java.net.HttpURLConnection.HTTP_OK;

import com.google.api.client.json.JsonFactory;
import com.google.api.services.cloudresourcemanager.model.Project;
import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.gcloud.resourcemanager.ResourceManagerOptions;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.joda.time.format.ISODateTimeFormat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;

/**
 * Utility to create a local Resource Manager mock for testing.
 *
 * <p>The mock runs in a separate thread, listening for HTTP requests on the local machine at an
 * ephemeral port.
 */
@SuppressWarnings("restriction")
public class LocalResourceManagerHelper {
  private static final Logger log = Logger.getLogger(LocalResourceManagerHelper.class.getName());
  private static final JsonFactory jsonFactory =
      new com.google.api.client.json.jackson.JacksonFactory();
  private static final Random PROJECT_NUMBER_GENERATOR = new Random();
  private static final String VERSION = "v1beta1";
  private static final String CONTEXT = "/" + VERSION + "/projects";
  private static final URI BASE_CONTEXT;
  private static final Set<String> SUPPORTED_COMPRESSION_ENCODINGS =
      ImmutableSet.of("gzip", "x-gzip");

  static {
    try {
      BASE_CONTEXT = new URI(CONTEXT);
    } catch (URISyntaxException e) {
      throw new RuntimeException(
          "Could not initialize LocalResourceManagerHelper due to URISyntaxException.", e);
    }
  }

  // see https://cloud.google.com/resource-manager/reference/rest/v1beta1/projects
  private static final Set<Character> PERMISSIBLE_PROJECT_NAME_PUNCTUATION =
      ImmutableSet.of('-', '\'', '"', ' ', '!');

  private final HttpServer server;
  private final ConcurrentHashMap<String, Project> projects = new ConcurrentHashMap<>();
  private final int port;

  private static class Response {
    private final int code;
    private final String body;

    Response(int code, String body) {
      this.code = code;
      this.body = body;
    }

    int code() {
      return code;
    }

    String body() {
      return body;
    }
  }

  private enum Error {
    ALREADY_EXISTS(409, "global", "alreadyExists", "ALREADY_EXISTS"),
    PERMISSION_DENIED(403, "global", "forbidden", "PERMISSION_DENIED"),
    FAILED_PRECONDITION(400, "global", "failedPrecondition", "FAILED_PRECONDITION"),
    INVALID_ARGUMENT(400, "global", "badRequest", "INVALID_ARGUMENT"),
    BAD_REQUEST(400, "global", "badRequest", "BAD_REQUEST"),
    INTERNAL_ERROR(500, "global", "internalError", "INTERNAL_ERROR");

    private final int code;
    private final String domain;
    private final String reason;
    private final String status;

    Error(int code, String domain, String reason, String status) {
      this.code = code;
      this.domain = domain;
      this.reason = reason;
      this.status = status;
    }

    Response response(String message) {
      try {
        return new Response(code, toJson(message));
      } catch (IOException e) {
        return Error.INTERNAL_ERROR.response("Error when generating JSON error response");
      }
    }

    private String toJson(String message) throws IOException {
      Map<String, Object> errors = new HashMap<>();
      errors.put("domain", domain);
      errors.put("message", message);
      errors.put("reason", reason);
      Map<String, Object> args = new HashMap<>();
      args.put("errors", ImmutableList.of(errors));
      args.put("code", code);
      args.put("message", message);
      args.put("status", status);
      return jsonFactory.toString(ImmutableMap.of("error", args));
    }
  }

  private class RequestHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) {
      // see https://cloud.google.com/resource-manager/reference/rest/
      Response response;
      String path = BASE_CONTEXT.relativize(exchange.getRequestURI()).getPath();
      String requestMethod = exchange.getRequestMethod();
      try {
        switch (requestMethod) {
          case "POST":
            if (path.endsWith(":undelete")) {
              response = undelete(projectIdFromUri(path));
            } else {
              String requestBody =
                  decodeContent(exchange.getRequestHeaders(), exchange.getRequestBody());
              response = create(jsonFactory.fromString(requestBody, Project.class));
            }
            break;
          case "DELETE":
            response = delete(projectIdFromUri(path));
            break;
          case "GET":
            if (!path.isEmpty()) {
              response =
                  get(projectIdFromUri(path), parseFields(exchange.getRequestURI().getQuery()));
            } else {
              response = list(parseListOptions(exchange.getRequestURI().getQuery()));
            }
            break;
          case "PUT":
            String requestBody =
                decodeContent(exchange.getRequestHeaders(), exchange.getRequestBody());
            response =
                replace(projectIdFromUri(path), jsonFactory.fromString(requestBody, Project.class));
            break;
          default:
            response = Error.BAD_REQUEST.response(
                "The server could not understand the following request URI: " + requestMethod + " "
                + path);
        }
      } catch (IOException e) {
        response = Error.BAD_REQUEST.response(e.getMessage());
      }
      writeResponse(exchange, response);
    }
  }

  private static void writeResponse(HttpExchange exchange, Response response) {
    exchange.getResponseHeaders().set("Content-type", "application/json; charset=UTF-8");
    OutputStream outputStream = exchange.getResponseBody();
    try {
      exchange.getResponseHeaders().add("Connection", "close");
      exchange.sendResponseHeaders(response.code(), response.body().length());
      outputStream.write(response.body().getBytes(StandardCharsets.UTF_8));
      outputStream.close();
    } catch (IOException e) {
      log.log(Level.WARNING, "IOException encountered when sending response.", e);
    }
  }

  private static String decodeContent(Headers headers, InputStream inputStream) throws IOException {
    List<String> contentEncoding = headers.get("Content-encoding");
    InputStream input = inputStream;
    try {
      if (contentEncoding != null && !contentEncoding.isEmpty()) {
        String encoding = contentEncoding.get(0);
        if (SUPPORTED_COMPRESSION_ENCODINGS.contains(encoding)) {
          input = new GZIPInputStream(inputStream);
        } else if (!encoding.equals("identity")) {
          throw new IOException(
              "The request has the following unsupported HTTP content encoding: " + encoding);
        }
      }
      return new String(ByteStreams.toByteArray(input), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IOException("Exception encountered when decoding request content.", e);
    }
  }

  private static String projectIdFromUri(String path) throws IOException {
    if (path.isEmpty()) {
      throw new IOException("The URI path '" + path + "' doesn't have a project ID.");
    }
    return path.split(":")[0];
  }

  private static String[] parseFields(String query) {
    if (query != null && !query.isEmpty()) {
      String[] querySplit = query.split("=");
      return querySplit.length > 1 ? querySplit[1].split(",") : null;
    }
    return null;
  }

  private static Map<String, Object> parseListOptions(String query) {
    Map<String, Object> options = new HashMap<>();
    if (query != null) {
      String[] args = query.split("&");
      for (String arg : args) {
        String[] argEntry = arg.split("=");
        switch (argEntry[0]) {
          case "fields":
            // List fields are in the form "projects(field1, field2, ...)"
            options.put(
                "fields",
                argEntry[1].substring("projects(".length(), argEntry[1].length() - 1).split(","));
            break;
          case "filter":
            options.put("filter", argEntry[1].split(" "));
            break;
          case "pageToken":
            // support pageToken when Cloud Resource Manager supports this (#421)
            break;
          case "pageSize":
            // support pageSize when Cloud Resource Manager supports this (#421)
            break;
        }
      }
    }
    return options;
  }

  private static String checkForProjectErrors(Project project) {
    if (project.getProjectId() == null) {
      return "Project ID cannot be empty.";
    }
    if (!isValidIdOrLabel(project.getProjectId(), 6, 30)) {
      return "Project " + project.getProjectId() + " has an invalid ID."
          + " See https://cloud.google.com/resource-manager/reference/rest/" + VERSION + "/projects"
          + " for more information.";
    }
    if (project.getName() != null) {
      for (char c : project.getName().toCharArray()) {
        if (!PERMISSIBLE_PROJECT_NAME_PUNCTUATION.contains(c) && !Character.isLetterOrDigit(c)) {
          return "Project " + project.getProjectId() + " has an invalid name."
              + " See https://cloud.google.com/resource-manager/reference/rest/" + VERSION
              + "/projects for more information.";
        }
      }
    }
    if (project.getLabels() != null) {
      if (project.getLabels().size() > 256) {
        return "Project " + project.getProjectId() + " exceeds the limit of 256 labels.";
      }
      for (Map.Entry<String, String> entry : project.getLabels().entrySet()) {
        if (!isValidIdOrLabel(entry.getKey(), 1, 63)
            || !isValidIdOrLabel(entry.getValue(), 0, 63)) {
          return "Project " + project.getProjectId() + " has an invalid label entry."
              + " See https://cloud.google.com/resource-manager/reference/rest/" + VERSION
              + "/projects for more information.";
        }
      }
    }
    return null;
  }

  private static boolean isValidIdOrLabel(String value, int minLength, int maxLength) {
    for (char c : value.toCharArray()) {
      if (c != '-' && !Character.isDigit(c) && !Character.isLowerCase(c)) {
        return false;
      }
    }
    if (!value.isEmpty() && (!Character.isLetter(value.charAt(0)) || value.endsWith("-"))) {
      return false;
    }
    return value.length() >= minLength && value.length() <= maxLength;
  }

  Response create(Project project) {
    String customErrorMessage = checkForProjectErrors(project);
    if (customErrorMessage != null) {
      return Error.INVALID_ARGUMENT.response(customErrorMessage);
    } else {
      project.setLifecycleState("ACTIVE");
      project.setProjectNumber(Math.abs(PROJECT_NUMBER_GENERATOR.nextLong() % Long.MAX_VALUE));
      project.setCreateTime(ISODateTimeFormat.dateTime().print(System.currentTimeMillis()));
      if (projects.putIfAbsent(project.getProjectId(), project) != null) {
        return Error.ALREADY_EXISTS.response(
            "A project with the same project ID (" + project.getProjectId() + ") already exists.");
      }
      try {
        String createdProjectStr = jsonFactory.toString(project);
        return new Response(HTTP_OK, createdProjectStr);
      } catch (IOException e) {
        return Error.INTERNAL_ERROR.response("Error serializing project " + project.getProjectId());
      }
    }
  }

  synchronized Response delete(String projectId) {
    Project project = projects.get(projectId);
    if (project == null) {
      return Error.PERMISSION_DENIED.response(
          "Error when deleting " + projectId + " because the project was not found.");
    }
    if (!project.getLifecycleState().equals("ACTIVE")) {
      return Error.FAILED_PRECONDITION.response(
          "Error when deleting " + projectId + " because the lifecycle state was not ACTIVE.");
    } else {
      project.setLifecycleState("DELETE_REQUESTED");
      return new Response(HTTP_OK, "{}");
    }
  }

  Response get(String projectId, String[] fields) {
    Project project = projects.get(projectId);
    if (project != null) {
      try {
        return new Response(HTTP_OK, jsonFactory.toString(extractFields(project, fields)));
      } catch (IOException e) {
        return Error.INTERNAL_ERROR.response(
            "Error when serializing project " + project.getProjectId());
      }
    } else {
      return Error.PERMISSION_DENIED.response("Project " + projectId + " not found.");
    }
  }

  Response list(Map<String, Object> options) {
    // Use pageSize and pageToken options when Cloud Resource Manager does so (#421)
    List<String> projectsSerialized = new ArrayList<>();
    String[] filters = (String[]) options.get("filter");
    if (filters != null && !isValidFilter(filters)) {
      return Error.INVALID_ARGUMENT.response("Could not parse the filter.");
    }
    String[] fields = (String[]) options.get("fields");
    for (Project p : projects.values()) {
      boolean includeProject = includeProject(p, filters);
      if (includeProject) {
        try {
          projectsSerialized.add(jsonFactory.toString(extractFields(p, fields)));
        } catch (IOException e) {
          return Error.INTERNAL_ERROR.response(
              "Error when serializing project " + p.getProjectId());
        }
      }
    }
    StringBuilder responseBody = new StringBuilder();
    responseBody.append("{\"projects\": [");
    Joiner.on(",").appendTo(responseBody, projectsSerialized);
    responseBody.append("]}");
    return new Response(HTTP_OK, responseBody.toString());
  }

  private static boolean isValidFilter(String[] filters) {
    for (String filter : filters) {
      String field = filter.toLowerCase().split(":")[0];
      if (!("id".equals(field) || "name".equals(field) || field.startsWith("labels."))) {
        return false;
      }
    }
    return true;
  }

  private static boolean includeProject(Project project, String[] filters) {
    if (filters == null) {
      return true;
    }
    for (String filter : filters) {
      String[] filterEntry = filter.toLowerCase().split(":");
      String filterType = filterEntry[0];
      if ("id".equals(filterType)) {
        if (!satisfiesFilter(project.getProjectId(), filterEntry[1])) {
          return false;
        }
      } else if ("name".equals(filterType)) {
        if (!satisfiesFilter(project.getName(), filterEntry[1])) {
          return false;
        }
      } else if (filterType.startsWith("labels.")) {
        String labelKey = filterType.substring("labels.".length());
        if (project.getLabels() != null) {
          String labelValue = project.getLabels().get(labelKey);
          if (!satisfiesFilter(labelValue, filterEntry[1])) {
            return false;
          }
        }
      }
    }
    return true;
  }

  private static boolean satisfiesFilter(String projectValue, String filterValue) {
    if (projectValue == null) {
      return false;
    }
    return "*".equals(filterValue) || filterValue.equals(projectValue.toLowerCase());
  }

  private static Project extractFields(Project fullProject, String[] fields) {
    if (fields == null) {
      return fullProject;
    }
    Project project = new Project();
    for (String field : fields) {
      switch (field) {
        case "createTime":
          project.setCreateTime(fullProject.getCreateTime());
          break;
        case "labels":
          project.setLabels(fullProject.getLabels());
          break;
        case "lifecycleState":
          project.setLifecycleState(fullProject.getLifecycleState());
          break;
        case "name":
          project.setName(fullProject.getName());
          break;
        case "parent":
          project.setParent(fullProject.getParent());
          break;
        case "projectId":
          project.setProjectId(fullProject.getProjectId());
          break;
        case "projectNumber":
          project.setProjectNumber(fullProject.getProjectNumber());
          break;
      }
    }
    return project;
  }

  synchronized Response replace(String projectId, Project project) {
    Project originalProject = projects.get(projectId);
    if (originalProject == null) {
      return Error.PERMISSION_DENIED.response(
          "Error when replacing " + projectId + " because the project was not found.");
    } else if (!originalProject.getLifecycleState().equals("ACTIVE")) {
      return Error.FAILED_PRECONDITION.response(
          "Error when replacing " + projectId + " because the lifecycle state was not ACTIVE.");
    } else if (!Objects.equal(originalProject.getParent(), project.getParent())) {
      return Error.INVALID_ARGUMENT.response(
          "The server currently only supports setting the parent once "
          + "and does not allow unsetting it.");
    }
    project.setProjectId(projectId);
    project.setLifecycleState(originalProject.getLifecycleState());
    project.setCreateTime(originalProject.getCreateTime());
    project.setProjectNumber(originalProject.getProjectNumber());
    // replace cannot fail because both this method and removeProject are synchronized
    projects.replace(projectId, project);
    try {
      return new Response(HTTP_OK, jsonFactory.toString(project));
    } catch (IOException e) {
      return Error.INTERNAL_ERROR.response("Error when serializing project " + projectId);
    }
  }

  synchronized Response undelete(String projectId) {
    Project project = projects.get(projectId);
    Response response;
    if (project == null) {
      response = Error.PERMISSION_DENIED.response(
          "Error when undeleting " + projectId + " because the project was not found.");
    } else if (!project.getLifecycleState().equals("DELETE_REQUESTED")) {
      response = Error.FAILED_PRECONDITION.response("Error when undeleting " + projectId
          + " because the lifecycle state was not DELETE_REQUESTED.");
    } else {
      project.setLifecycleState("ACTIVE");
      response = new Response(HTTP_OK, "{}");
    }
    return response;
  }

  private LocalResourceManagerHelper() {
    try {
      server = HttpServer.create(new InetSocketAddress(0), 0);
      port = server.getAddress().getPort();
      server.createContext(CONTEXT, new RequestHandler());
    } catch (IOException e) {
      throw new RuntimeException("Could not bind the mock Resource Manager server.", e);
    }
  }

  /**
   * Creates a LocalResourceManagerHelper object that listens to requests on the local machine.
   */
  public static LocalResourceManagerHelper create() {
    return new LocalResourceManagerHelper();
  }

  /**
   * Returns a ResourceManagerOptions instance that sets the host to use the mock server.
   */
  public ResourceManagerOptions options() {
    return ResourceManagerOptions.builder().host("http://localhost:" + port).build();
  }

  /**
   * Starts the thread that runs the Resource Manager server.
   */
  public void start() {
    server.start();
  }

  /**
   * Stops the thread that runs the mock Resource Manager server.
   */
  public void stop() {
    server.stop(1);
  }

  /**
   * Utility method to change the lifecycle state of the specified project.
   *
   * @return true if the lifecycle state was successfully updated, false otherwise
   */
  public synchronized boolean changeLifecycleState(String projectId, String lifecycleState) {
    checkArgument(
        "ACTIVE".equals(lifecycleState) || "DELETE_REQUESTED".equals(lifecycleState)
        || "DELETE_IN_PROGRESS".equals(lifecycleState),
        "Lifecycle state must be ACTIVE, DELETE_REQUESTED, or DELETE_IN_PROGRESS");
    Project project = projects.get(checkNotNull(projectId));
    if (project != null) {
      project.setLifecycleState(lifecycleState);
      return true;
    }
    return false;
  }

  /**
   * Utility method to remove the specified project.
   *
   * <p>This method can be used to fully remove a project (to mimic when the server completely
   * deletes a project).
   *
   * @return true if the project was successfully deleted, false if the project didn't exist
   */
  public synchronized boolean removeProject(String projectId) {
    // Because this method is synchronized, any code that relies on non-atomic read/write operations
    // should not fail if that code is also synchronized.
    return projects.remove(checkNotNull(projectId)) != null;
  }
}
