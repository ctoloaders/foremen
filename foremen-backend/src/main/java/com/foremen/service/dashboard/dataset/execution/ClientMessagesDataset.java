package com.foremen.service.dashboard.dataset.execution;

import com.foremen.dao.model.AuthorSide;
import com.foremen.dao.model.ClientMessageEntity;
import com.foremen.service.dashboard.config.DatasetSchema.FieldType;
import com.foremen.service.dashboard.dataset.FieldDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** {@code CLIENT_MESSAGES}: project-scoped message metadata only; no text, translations or media. */
@Component
public class ClientMessagesDataset extends ExecutionDataset {

    public ClientMessagesDataset() {
        super("CLIENT_MESSAGES", "CLIENT_MESSAGES", ClientMessageEntity.class, "createdDate", List.of(
                FieldDescriptor.of("id", FieldType.ID),
                FieldDescriptor.of("project.id", FieldType.ID),
                FieldDescriptor.of("project.name", FieldType.STRING),
                FieldDescriptor.of("author.name", FieldType.STRING),
                enumField("authorSide", AuthorSide.class),
                FieldDescriptor.of("createdDate", FieldType.DATETIME),
                FieldDescriptor.of("readAt", FieldType.DATETIME),
                FieldDescriptor.computed("unread", FieldType.BOOLEAN, (cb, q, root) ->
                                cb.<Boolean>selectCase()
                                        .when(cb.isNull(root.get("readAt")), true)
                                        .otherwise(false))
                        .withFlags(true, true, true, false)));
    }

    @Override
    public Optional<String> projectPath() {
        return Optional.of("project.id");
    }
}
