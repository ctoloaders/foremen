package com.foremen.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import com.foremen.config.i18n.MessageResolver;
import com.foremen.config.roomsimport.RoomsImportProperties;
import com.foremen.config.security.PermissionResolver;
import com.foremen.config.security.PermissionResource;
import com.foremen.controller.advice.ForemenControllerAdvice;
import com.foremen.controller.model.RoomsImportJobDto;
import com.foremen.controller.model.mapper.RoomControllerMapper;
import com.foremen.dao.model.RoomsImportStatus;
import com.foremen.exception.ForemenApiException;
import com.foremen.service.ProjectAccessCache;
import com.foremen.service.RoomService;
import com.foremen.service.estimate.recalc.RoomsSaveService;
import com.foremen.service.model.mapper.AuditServiceMapper;
import com.foremen.service.permission.ForemenPermissionEvaluator;
import com.foremen.service.roomsimport.RoomsImportAccessGuard;
import com.foremen.service.roomsimport.RoomsImportErrors;
import com.foremen.service.roomsimport.RoomsImportInProgressException;
import com.foremen.service.roomsimport.RoomsImportJobReader;
import com.foremen.service.roomsimport.RoomsImportService;
import com.foremen.service.roomsimport.RoomsImportUploadValidator;

/**
 * FOR-05-02 task 4.2 — {@link RoomsImportController}: multipart validation, status mapping
 * ({@code 202/400/413/415/429/503/204/409/404}), the {@code 429} envelope (params + {@code Retry-After}),
 * ABAC completeness, project-access {@code 404}s and path precedence versus {@code /api/rooms/{id}}.
 *
 * <p>Standalone MockMvc with the real controller, validator, access guard and
 * {@link ForemenControllerAdvice}; the message source falls back to the code.
 */
class RoomsImportControllerTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'};

    private RoomsImportService service;
    private RoomsImportJobReader reader;
    private ProjectAccessCache accessCache;
    private RoomService roomService;
    private MockMvc mockMvc;
    private final PermissionResolver resolver = new PermissionResolver();

    @BeforeEach
    void setUp() {
        service = mock(RoomsImportService.class);
        reader = mock(RoomsImportJobReader.class);
        accessCache = mock(ProjectAccessCache.class);
        roomService = mock(RoomService.class);

        RoomsImportProperties props = new RoomsImportProperties(true, null,
                new RoomsImportProperties.Limits(2, DataSize.ofKilobytes(1), 3, 40), null, 0, null);
        RoomsImportController controller = new RoomsImportController(service, reader,
                new RoomsImportUploadValidator(props), new RoomsImportAccessGuard(accessCache));
        RoomController roomController = new RoomController(roomService, mock(RoomControllerMapper.class),
                mock(AuditServiceMapper.class), mock(RoomsSaveService.class), mock(ForemenPermissionEvaluator.class));

        ForemenControllerAdvice advice = new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource()));
        mockMvc = MockMvcBuilders.standaloneSetup(roomController, controller).setControllerAdvice(advice).build();

        authenticate("1", "ROLE_ADMIN");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, "n/a", List.of(new SimpleGrantedAuthority(role))));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("files", name, contentType, bytes);
    }

    private static RoomsImportJobDto dto(Long id, Long projectId, RoomsImportStatus status) {
        return new RoomsImportJobDto(id, projectId, status, status, null, LocalDateTime.of(2026, 1, 1, 10, 0),
                null, null, null);
    }

    // --- ABAC -----------------------------------------------------------------------------------

    @Test
    @DisplayName("class carries @PermissionResource(ROOMS); every handler is COMPLETE with CREATE/READ per design")
    void abacMapping() {
        assertThat(RoomsImportController.class.getAnnotation(PermissionResource.class).value()).isEqualTo("ROOMS");
        assertPair("start", "CREATE");
        assertPair("current", "READ");
        assertPair("get", "READ");
        assertPair("cancel", "CREATE");
        assertPair("discard", "CREATE");
        assertPair("draftMedia", "READ");

        RoomsImportController bare = new RoomsImportController(null, null, null, null);
        List<Method> handlers = Arrays.stream(RoomsImportController.class.getDeclaredMethods())
                .filter(m -> org.springframework.core.annotation.AnnotatedElementUtils
                        .hasAnnotation(m, RequestMapping.class))
                .toList();
        assertThat(handlers).hasSize(6);
        for (Method m : handlers) {
            assertThat(resolver.classifyCompleteness(new HandlerMethod(bare, m)))
                    .as("handler %s must be COMPLETE", m.getName())
                    .isEqualTo(PermissionResolver.Completeness.COMPLETE);
        }
    }

    private void assertPair(String methodName, String operation) {
        Method method = Arrays.stream(RoomsImportController.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName)).findFirst().orElseThrow();
        assertThat(resolver.resolve(new HandlerMethod(new RoomsImportController(null, null, null, null), method)))
                .as(methodName)
                .isEqualTo(new PermissionResolver.ResolvedPair("ROOMS", operation));
    }

    // --- start ----------------------------------------------------------------------------------

    @Test
    @DisplayName("start with prompt only → 202 {jobId,status}; service receives no files")
    void startPromptOnly() throws Exception {
        when(service.start(eq(3L), eq(1L), eq("3 rooms"), anyList()))
                .thenReturn(new RoomsImportService.StartResult(42L, RoomsImportStatus.QUEUED));

        mockMvc.perform(multipart("/api/rooms/project/3/autoimport").param("prompt", "3 rooms"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(42))
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    @DisplayName("start with an image and a text file → 202; empty file parts are dropped")
    void startWithFiles() throws Exception {
        when(service.start(eq(3L), eq(1L), any(), anyList()))
                .thenReturn(new RoomsImportService.StartResult(43L, RoomsImportStatus.QUEUED));

        mockMvc.perform(multipart("/api/rooms/project/3/autoimport")
                        .file(file("plan.png", "image/png", PNG))
                        .file(file("notes.txt", "text/plain", "Kitchen 3x4".getBytes(StandardCharsets.UTF_8)))
                        .file(file("", "application/octet-stream", new byte[0])))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").value(43));
    }

    @Test
    @DisplayName("blank prompt and no files → 400 error.roomsimport.input.empty; service not called")
    void startEmpty() throws Exception {
        mockMvc.perform(multipart("/api/rooms/project/3/autoimport").param("prompt", "   ")
                        .file(file("", "application/octet-stream", new byte[0])))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.INPUT_EMPTY));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("unsupported content → 415 error.roomsimport.file.type naming the file in params")
    void startUnsupportedType() throws Exception {
        mockMvc.perform(multipart("/api/rooms/project/3/autoimport")
                        .file(file("tool.exe", "application/octet-stream", new byte[] {'M', 'Z', 1, 2, 3, 4})))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.FILE_TYPE))
                .andExpect(jsonPath("$.params.fileName").value("tool.exe"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PNG bytes behind a disallowed extension → 415 (type is checked by content AND extension)")
    void startDisallowedExtension() throws Exception {
        mockMvc.perform(multipart("/api/rooms/project/3/autoimport")
                        .file(file("plan.exe", "image/png", PNG)))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("more files than the limit → 400 error.roomsimport.file.count with the limit in params")
    void startTooManyFiles() throws Exception {
        mockMvc.perform(multipart("/api/rooms/project/3/autoimport")
                        .file(file("a.png", "image/png", PNG))
                        .file(file("b.png", "image/png", PNG))
                        .file(file("c.png", "image/png", PNG)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.FILE_COUNT))
                .andExpect(jsonPath("$.params.limit").value(2));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("a file above the size limit → 413 error.roomsimport.file.size")
    void startTooLargeFile() throws Exception {
        mockMvc.perform(multipart("/api/rooms/project/3/autoimport")
                        .file(file("big.txt", "text/plain", new byte[2048])))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.FILE_SIZE))
                .andExpect(jsonPath("$.params.fileName").value("big.txt"));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("PDF pages above the total limit → 400 error.roomsimport.pdf.pages")
    void startTooManyPdfPages() throws Exception {
        byte[] pdf = pdfWithPages(4);
        // size limit in this fixture is 1 KB; a 4-page blank PDF is larger, so use a dedicated mockMvc
        RoomsImportProperties props = new RoomsImportProperties(true, null,
                new RoomsImportProperties.Limits(5, DataSize.ofMegabytes(1), 3, 40), null, 0, null);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RoomsImportController(service, reader,
                        new RoomsImportUploadValidator(props), new RoomsImportAccessGuard(accessCache)))
                .setControllerAdvice(new ForemenControllerAdvice(new MessageResolver(new StaticMessageSource())))
                .build();

        mvc.perform(multipart("/api/rooms/project/3/autoimport").file(file("plan.pdf", "application/pdf", pdf)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.PDF_PAGES))
                .andExpect(jsonPath("$.params.limit").value(3))
                .andExpect(jsonPath("$.params.pages").value(4));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("feature disabled → 503 error.roomsimport.unavailable")
    void startDisabled() throws Exception {
        when(service.start(anyLong(), anyLong(), any(), anyList()))
                .thenThrow(new ForemenApiException(HttpStatus.SERVICE_UNAVAILABLE, RoomsImportErrors.UNAVAILABLE));

        mockMvc.perform(multipart("/api/rooms/project/3/autoimport").param("prompt", "x"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.UNAVAILABLE));
    }

    @Test
    @DisplayName("active job → 429 in_progress with Retry-After header and params {jobId, stage, startedAt}")
    void startInProgress() throws Exception {
        LocalDateTime startedAt = LocalDateTime.of(2026, 1, 1, 10, 0);
        when(service.start(anyLong(), anyLong(), any(), anyList()))
                .thenThrow(new RoomsImportInProgressException(77L, RoomsImportStatus.MEASURING, startedAt));

        mockMvc.perform(multipart("/api/rooms/project/3/autoimport").param("prompt", "x"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.IN_PROGRESS))
                .andExpect(jsonPath("$.params.jobId").value(77))
                .andExpect(jsonPath("$.params.stage").value("MEASURING"))
                .andExpect(jsonPath("$.params.startedAt").value(startedAt.toString()));
    }

    @Test
    @DisplayName("project outside the caller's access → 404 and nothing is validated or started")
    void startForeignProject() throws Exception {
        authenticate("7", "ROLE_MANAGER");
        when(accessCache.get(7L)).thenReturn(Set.of(1L));

        mockMvc.perform(multipart("/api/rooms/project/3/autoimport").param("prompt", "x"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("member of the project may start (non-ADMIN with access)")
    void startMember() throws Exception {
        authenticate("7", "ROLE_MANAGER");
        when(accessCache.get(7L)).thenReturn(Set.of(3L));
        when(service.start(eq(3L), eq(7L), any(), anyList()))
                .thenReturn(new RoomsImportService.StartResult(5L, RoomsImportStatus.QUEUED));

        mockMvc.perform(multipart("/api/rooms/project/3/autoimport").param("prompt", "x"))
                .andExpect(status().isAccepted());
    }

    // --- current / get --------------------------------------------------------------------------

    @Test
    @DisplayName("current: 200 with the job, 204 when the project has none")
    void current() throws Exception {
        when(reader.currentOfProject(3L)).thenReturn(Optional.of(dto(9L, 3L, RoomsImportStatus.EXTRACTING)));
        when(reader.currentOfProject(4L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/rooms/project/3/autoimport/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(9))
                .andExpect(jsonPath("$.projectId").value(3))
                .andExpect(jsonPath("$.status").value("EXTRACTING"))
                .andExpect(jsonPath("$.stage").value("EXTRACTING"))
                .andExpect(jsonPath("$.result").doesNotExist());
        mockMvc.perform(get("/api/rooms/project/4/autoimport/current")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("current for a foreign project → 404 without reading the job")
    void currentForeign() throws Exception {
        authenticate("7", "ROLE_MANAGER");
        when(accessCache.get(7L)).thenReturn(Set.of(1L));

        mockMvc.perform(get("/api/rooms/project/3/autoimport/current")).andExpect(status().isNotFound());
        verifyNoInteractions(reader);
    }

    @Test
    @DisplayName("get: 200 for own job, 404 for unknown and for a foreign job")
    void getJob() throws Exception {
        when(reader.byId(9L)).thenReturn(Optional.of(dto(9L, 3L, RoomsImportStatus.QUEUED)));
        when(reader.byId(10L)).thenReturn(Optional.of(dto(10L, 99L, RoomsImportStatus.QUEUED)));
        when(reader.byId(11L)).thenReturn(Optional.empty());
        authenticate("7", "ROLE_MANAGER");
        when(accessCache.get(7L)).thenReturn(Set.of(3L));

        mockMvc.perform(get("/api/rooms/autoimport/9")).andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(9));
        mockMvc.perform(get("/api/rooms/autoimport/10")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/rooms/autoimport/11")).andExpect(status().isNotFound());
    }

    // --- cancel / discard -----------------------------------------------------------------------

    @Test
    @DisplayName("cancel: 204 for an active job; 409 not_cancellable when terminal; 404 when gone or foreign")
    void cancel() throws Exception {
        when(reader.projectIdOf(9L)).thenReturn(Optional.of(3L));
        when(reader.projectIdOf(10L)).thenReturn(Optional.of(3L));
        when(reader.projectIdOf(11L)).thenReturn(Optional.empty());
        when(reader.projectIdOf(12L)).thenReturn(Optional.of(99L));
        doThrow(new ForemenApiException(HttpStatus.CONFLICT, RoomsImportErrors.NOT_CANCELLABLE, 10L))
                .when(service).cancel(10L);
        authenticate("7", "ROLE_MANAGER");
        when(accessCache.get(7L)).thenReturn(Set.of(3L));

        mockMvc.perform(post("/api/rooms/autoimport/9/cancel")).andExpect(status().isNoContent());
        verify(service).cancel(9L);
        mockMvc.perform(post("/api/rooms/autoimport/10/cancel")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(RoomsImportErrors.NOT_CANCELLABLE));
        mockMvc.perform(post("/api/rooms/autoimport/11/cancel")).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rooms/autoimport/12/cancel")).andExpect(status().isNotFound());
        verify(service, never()).cancel(12L);
    }

    @Test
    @DisplayName("discard: 204 and delegates; idempotent 204 when gone; 404 for a foreign job (not deleted)")
    void discard() throws Exception {
        when(reader.projectIdOf(9L)).thenReturn(Optional.of(3L));
        when(reader.projectIdOf(11L)).thenReturn(Optional.empty());
        when(reader.projectIdOf(12L)).thenReturn(Optional.of(99L));
        authenticate("7", "ROLE_MANAGER");
        when(accessCache.get(7L)).thenReturn(Set.of(3L));

        mockMvc.perform(delete("/api/rooms/autoimport/9")).andExpect(status().isNoContent());
        verify(service).discard(9L);
        mockMvc.perform(delete("/api/rooms/autoimport/11")).andExpect(status().isNoContent());
        verify(service, never()).discard(11L);
        mockMvc.perform(delete("/api/rooms/autoimport/12")).andExpect(status().isNotFound());
        verify(service, never()).discard(12L);
    }

    // --- draft media ----------------------------------------------------------------------------

    @Test
    @DisplayName("draft media: streams PNG bytes with nosniff; 404 when not listed / gone / foreign")
    void draftMedia() throws Exception {
        String mediaId = "0b9f7c1e-2f43-4b0e-8a4e-1d3c5a7e9f10.png";
        when(reader.projectIdOf(9L)).thenReturn(Optional.of(3L));
        when(reader.projectIdOf(12L)).thenReturn(Optional.of(99L));
        when(reader.draftMedia(9L, mediaId)).thenReturn(Optional.of(PNG));
        when(reader.draftMedia(eq(9L), eq("missing.png"))).thenReturn(Optional.empty());
        authenticate("7", "ROLE_MANAGER");
        when(accessCache.get(7L)).thenReturn(Set.of(3L));

        byte[] body = mockMvc.perform(get("/api/rooms/autoimport/9/media/" + mediaId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).isEqualTo(PNG);

        mockMvc.perform(get("/api/rooms/autoimport/9/media/missing.png")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/rooms/autoimport/12/media/" + mediaId)).andExpect(status().isNotFound());
        verify(reader, never()).draftMedia(eq(12L), any());
    }

    // --- path precedence --------------------------------------------------------------------------

    @Test
    @DisplayName("/api/rooms/autoimport/** goes to the import controller, /api/rooms/{id} to RoomController")
    void pathPrecedence() throws Exception {
        when(reader.byId(9L)).thenReturn(Optional.of(dto(9L, 3L, RoomsImportStatus.QUEUED)));

        mockMvc.perform(get("/api/rooms/autoimport/9")).andExpect(status().isOk());
        verify(reader).byId(9L);
        verifyNoInteractions(roomService);

        mockMvc.perform(get("/api/rooms/5"));
        verify(roomService).findById(5L);

        when(reader.projectIdOf(9L)).thenReturn(Optional.of(3L));
        mockMvc.perform(delete("/api/rooms/autoimport/9")).andExpect(status().isNoContent());
        verify(service).discard(9L);
        verify(roomService, never()).deleteById(9L);

        when(reader.currentOfProject(3L)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/rooms/project/3/autoimport/current")).andExpect(status().isNoContent());
    }

    // --- helpers --------------------------------------------------------------------------------

    private static byte[] pdfWithPages(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                doc.addPage(new PDPage());
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
