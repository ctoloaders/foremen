package com.foremen.qa.steps;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.foremen.qa.fixtures.OfferEstimateFixture;
import com.foremen.qa.fixtures.TestUser;
import com.foremen.qa.fixtures.TestUserFixture;
import com.foremen.qa.support.ApiHelper;
import com.foremen.qa.support.DataGen;
import com.foremen.qa.support.QaHttp;
import com.foremen.qa.support.QaHttp.Resp;
import com.foremen.qa.support.TestConfig;
import com.foremen.qa.support.World;
import com.google.gson.JsonParser;

/**
 * Per-scenario state shared by the FOR-05-07b offers steps (API and UI).
 *
 * <p>Holds the HTTP client, the acting users and their tokens, the run-created project with rooms,
 * estimate lines and offers, and a variable table for the curly-brace placeholders used in Gherkin
 * paths, bodies and expectations (for example PID, ROOM_A, WORK_1, NET0 or an offer alias such as A).
 * Picocontainer builds one instance per scenario and injects it into every step class, so the API
 * steps prepare the data that the UI steps then drive.
 *
 * <p>Repeatability: project names, emails and offer names carry the run id ({@link DataGen}); the
 * teardown registered in {@link World} deletes the rooms, then the project (estimate, lines, room
 * quantities and offers cascade), then the users. A second project uses the variable prefix X_.
 */
public class OfferCtx {

    private static final Pattern VAR = Pattern.compile("[{]([A-Za-z0-9_]+)[}]");

    public final World world;
    public final OfferEstimateFixture fx = new OfferEstimateFixture();
    public final TestUserFixture users = new TestUserFixture();
    public final Map<String, String> vars = new LinkedHashMap<>();
    public final Map<String, String> remembered = new LinkedHashMap<>();

    /** The last HTTP response of the scenario. */
    public Resp last;

    public TestUser client;
    public TestUser outsider;

    private QaHttp http;
    private final Map<String, String> tokens = new LinkedHashMap<>();

    public OfferCtx(World world) {
        this.world = world;
        vars.put("RUN", DataGen.runId());
        vars.put("NAME_255", "N".repeat(255));
        vars.put("NAME_256", "N".repeat(256));
    }

    // ---- HTTP and tokens ----

    public QaHttp http() {
        if (http == null) {
            http = QaHttp.create();
            world.registerTeardown(this::closeHttp);
        }
        return http;
    }

    private void closeHttp() {
        if (http != null) {
            http.close();
            http = null;
        }
    }

    /** Bearer token for an actor: admin, client, outsider or anonymous (null). */
    public String token(String actor) {
        String key = actor.toLowerCase();
        if ("anonymous".equals(key)) {
            return null;
        }
        return tokens.computeIfAbsent(key, this::login);
    }

    private String login(String actor) {
        switch (actor) {
            case "admin":
                return http().login(TestConfig.adminEmail(), TestConfig.adminPassword());
            case "client":
                return otpLogin(require(client, "client"));
            case "outsider":
                return otpLogin(require(outsider, "outsider client"));
            default:
                throw new IllegalArgumentException("Unknown actor " + actor + "; use admin, client, outsider or anonymous");
        }
    }

    /** Sign a client in the way the UI does: request an OTP, read it from otp_tokens, verify it. */
    public String otpLogin(TestUser user) {
        http().otpRequest(user.email());
        String code = waitForOtp(user.email());
        return http().otpVerify(user.email(), code);
    }

    /** Poll otp_tokens for the newest live code (the row is written right after the request). */
    public String waitForOtp(String email) {
        for (int i = 0; i < 40; i++) {
            String code = fx.latestOtp(email);
            if (code != null) {
                return code;
            }
            sleep(250);
        }
        throw new IllegalStateException("No OTP code appeared for " + email);
    }

    private static TestUser require(TestUser user, String what) {
        if (user == null) {
            throw new IllegalStateException("No " + what + " in scope; create it in a Given step first.");
        }
        return user;
    }

    // ---- Variables ----

    /** Replace placeholders with variable values; unknown names stay untouched. */
    public String resolve(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = VAR.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(value != null ? value : m.group(0)));
        }
        m.appendTail(out);
        return out.toString();
    }

    public String var(String name) {
        String v = vars.get(name);
        if (v == null) {
            throw new IllegalStateException("Variable " + name + " is not set. Known: " + vars.keySet());
        }
        return v;
    }

    public long id(String name) {
        return Long.parseLong(var(name));
    }

    // ---- Users ----

    public TestUser newClient() {
        TestUser u = users.createActiveUser("CLIENT");
        world.registerTeardown(new UserCleanup(users, u));
        return u;
    }

    private record UserCleanup(TestUserFixture fixture, TestUser user) implements Runnable {
        @Override
        public void run() {
            fixture.deleteUserAndResources(user);
        }
    }

    private record ProjectCleanup(World world, long projectId) implements Runnable {
        @Override
        public void run() {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteProject(projectId);
            } catch (RuntimeException ignored) {
                // best-effort: the project may already be gone
            }
        }
    }

    private record RoomCleanup(World world, long roomId) implements Runnable {
        @Override
        public void run() {
            ApiHelper t = world.api();
            t.loginAdmin();
            try {
                t.deleteRoom(roomId);
            } catch (RuntimeException ignored) {
                // best-effort: a scenario may have deleted the room itself
            }
        }
    }

    // ---- Project, rooms, estimate ----

    /**
     * Create a project with the given client members and rooms A, B and C through the admin API.
     * The prefix is empty for the main project and X_ for a second one.
     */
    public void createProject(String prefix, List<TestUser> clients) {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        List<Map<String, Object>> members = new ArrayList<>();
        for (TestUser c : clients) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", c.id());
            m.put("projectRoleId", api.resolveRoleIdByCode("CLIENT"));
            members.add(m);
        }
        String name = "FOR-05-07b " + DataGen.runId() + " #" + DataGen.nextSeq();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("members", members);
        long pid = api.createProject(body);
        world.registerTeardown(new ProjectCleanup(world, pid));
        vars.put(prefix + "PID", String.valueOf(pid));
        vars.put(prefix + "PROJECT_NAME", name);
        long roomTypeId = api.resolveFirstRowId("/api/room-types");
        vars.put("ROOM_TYPE", String.valueOf(roomTypeId));
        for (String letter : new String[] {"A", "B", "C"}) {
            String label = "Room " + letter + " " + DataGen.runId() + (prefix.isEmpty() ? "" : " X");
            Map<String, Object> room = new LinkedHashMap<>();
            room.put("projectId", pid);
            room.put("roomTypeId", roomTypeId);
            room.put("label", label);
            room.put("ceilingHeight", 2.5);
            room.put("floorArea", 10);
            room.put("wallArea", 25);
            long rid = api.createRoom(room);
            world.registerTeardown(new RoomCleanup(world, rid));
            vars.put(prefix + "ROOM_" + letter, String.valueOf(rid));
            vars.put(prefix + "ROOM_" + letter + "_LABEL", label);
        }
        Resp est = http().get("/api/estimates/project/" + pid, token("admin"));
        vars.put(prefix + "EID", String.valueOf(est.obj().get("id").getAsLong()));
    }

    /**
     * Fill the estimate through the API: work 1 in room A (2) and room B (3), work 2 in room A (1.5),
     * work 3 applied to no room. The works sit in three different categories; their prices come from
     * the work price list, so the expected net total NET0 is read back from the estimate.
     */
    public void fillEstimate(String prefix) {
        long eid = id(prefix + "EID");
        List<long[]> works = fx.workItemsInDistinctCategories(3);
        for (int i = 0; i < 3; i++) {
            long line = addLine(eid, works.get(i), i + 1);
            vars.put(prefix + "LINE_" + (i + 1), String.valueOf(line));
            vars.put(prefix + "WORK_" + (i + 1), String.valueOf(works.get(i)[0]));
            vars.put(prefix + "CAT_" + (i + 1), String.valueOf(works.get(i)[2]));
            vars.put(prefix + "CATKEY_" + (i + 1), fx.categoryKey(works.get(i)[2]));
        }
        vars.put(prefix + "QTY_1A", String.valueOf(addQuantity(id(prefix + "LINE_1"), id(prefix + "ROOM_A"), "2")));
        vars.put(prefix + "QTY_1B", String.valueOf(addQuantity(id(prefix + "LINE_1"), id(prefix + "ROOM_B"), "3")));
        vars.put(prefix + "QTY_2A", String.valueOf(addQuantity(id(prefix + "LINE_2"), id(prefix + "ROOM_A"), "1.5")));
        vars.put(prefix + "NET0", estimateNet(prefix));
    }

    /** Add an estimate line for the work {workItemId, unitId}; returns the line id. */
    public long addLine(long estimateId, long[] work, int lineNo) {
        String body = "{\"estimateId\":" + estimateId + ",\"workItemId\":" + work[0] + ",\"unitId\":" + work[1]
                + ",\"lineNo\":" + lineNo + ",\"unitPrice\":100}";
        Resp r = http().post("/api/estimate-lines", token("admin"), body);
        if (r.status() != 200 && r.status() != 201) {
            throw new IllegalStateException("POST /api/estimate-lines -> " + r.status() + " " + r.abbreviate());
        }
        return r.obj().get("id").getAsLong();
    }

    /** Add the quantity of a line in a room; returns the room-quantity row id. */
    public long addQuantity(long lineId, long roomId, String quantity) {
        String body = "{\"lineId\":" + lineId + ",\"roomId\":" + roomId + ",\"quantity\":" + quantity + "}";
        Resp r = http().post("/api/estimate-line-room-qty", token("admin"), body);
        if (r.status() != 200 && r.status() != 201) {
            throw new IllegalStateException("POST /api/estimate-line-room-qty -> " + r.status() + " " + r.abbreviate());
        }
        return r.obj().get("id").getAsLong();
    }

    /** The live estimate net total as returned by the estimate API. */
    public String estimateNet(String prefix) {
        Resp est = http().get("/api/estimates/project/" + var(prefix + "PID"), token("admin"));
        return est.obj().get("totalNet").getAsBigDecimal().stripTrailingZeros().toPlainString();
    }

    /** Add one more room to the main project (with teardown) and store ROOM_{letter} and its label. */
    public void addRoom(String letter) {
        ApiHelper api = world.api();
        if (api.accessToken() == null) {
            api.loginAdmin();
        }
        String label = "Room " + letter + " " + DataGen.runId();
        Map<String, Object> room = new LinkedHashMap<>();
        room.put("projectId", id("PID"));
        room.put("roomTypeId", id("ROOM_TYPE"));
        room.put("label", label);
        room.put("ceilingHeight", 2.5);
        room.put("floorArea", 10);
        room.put("wallArea", 25);
        long rid = api.createRoom(room);
        world.registerTeardown(new RoomCleanup(world, rid));
        vars.put("ROOM_" + letter, String.valueOf(rid));
        vars.put("ROOM_" + letter + "_LABEL", label);
    }

    // ---- Calls ----

    /** Call the API as the actor; path and body may contain placeholders. */
    public Resp call(String actor, String method, String path, String body) {
        String payload = null;
        if (body != null && !body.isBlank()) {
            payload = JsonParser.parseString(resolve(body)).toString();
        }
        last = http().call(method.toUpperCase(), resolve(path), token(actor), payload, null);
        return last;
    }

    /** Call the API as the actor with an Accept-Language header. */
    public Resp callLang(String actor, String method, String path, String lang) {
        last = http().call(method.toUpperCase(), resolve(path), token(actor), null, lang);
        return last;
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
