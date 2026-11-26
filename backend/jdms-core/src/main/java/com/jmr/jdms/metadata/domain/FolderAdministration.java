package com.jmr.jdms.metadata.domain;

import com.jmr.jdms.metadata.api.FolderUsage;
import com.jmr.jdms.shared.error.ApiException;
import com.jmr.jdms.shared.error.ValidationFailedException;
import com.jmr.jdms.shared.error.Violation;
import com.jmr.jdms.shared.id.Ids;
import com.jmr.jdms.shared.security.Roles;
import com.jmr.jdms.workflow.api.ApplyResult;
import com.jmr.jdms.workflow.api.ChangeHandler;
import com.jmr.jdms.workflow.api.ChangeOperation;
import com.jmr.jdms.workflow.api.ChangeProposal;
import com.jmr.jdms.workflow.api.ChangeRequests;
import com.jmr.jdms.workflow.api.PendingChangeView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Virtual folders: reads, proposals and applying approved changes. Sibling names are unique (ignoring case), a
 * folder cannot move into its own subtree, and depth is limited.
 */
@Service
public class FolderAdministration implements ChangeHandler {

    public static final String SUBJECT = "FOLDER";
    public static final String FOLDER_NOT_FOUND = "FOLDER_NOT_FOUND";
    public static final String FOLDER_NAME_IN_USE = "FOLDER_NAME_IN_USE";
    public static final String FOLDER_NOT_EMPTY = "FOLDER_NOT_EMPTY";
    static final int MAX_DEPTH = 20;

    private static final Pattern NAME = Pattern.compile("[^/\\\\\\p{Cntrl}]{1,200}");

    private final FolderRepository folders;
    private final CabinetRepository cabinets;
    private final ChangeRequests changeRequests;
    private final MetadataChangeSupport support;
    private final ObjectProvider<FolderUsage> folderUsages;

    FolderAdministration(FolderRepository folders, CabinetRepository cabinets, ChangeRequests changeRequests,
            MetadataChangeSupport support, ObjectProvider<FolderUsage> folderUsages) {
        this.folders = folders;
        this.cabinets = cabinets;
        this.changeRequests = changeRequests;
        this.support = support;
        this.folderUsages = folderUsages;
    }

    /** Proposed folder: name and parent (null = cabinet root) within a cabinet. */
    public record FolderPayload(UUID cabinetId, UUID parentId, String name) {
    }

    public record FolderView(UUID id, UUID cabinetId, UUID parentId, String name, String path, long version) {

        static FolderView of(Folder folder) {
            return new FolderView(folder.getId(), folder.getCabinetId(), folder.getParentId(), folder.getName(),
                    folder.getPathCache(), folder.getVersionLock());
        }
    }

    @Transactional(readOnly = true)
    public Page<FolderView> children(UUID cabinetId, UUID parentId, Pageable pageable) {
        loadCabinet(cabinetId);
        Page<Folder> page = parentId == null ? folders.findByCabinetIdAndParentIdIsNull(cabinetId, pageable)
                : folders.findByCabinetIdAndParentId(cabinetId, parentId, pageable);
        return page.map(FolderView::of);
    }

    @Transactional(readOnly = true)
    public FolderView get(UUID id) {
        return FolderView.of(load(id));
    }

    @Transactional
    public PendingChangeView proposeCreate(UUID cabinetId, UUID parentId, String name) {
        Cabinet cabinet = loadCabinet(cabinetId);
        String cleanName = cleanName(name);
        Map<UUID, Folder> tree = tree(cabinetId);
        Optional<String> problem = placementProblem(tree, null, parentId, cleanName);
        problem.ifPresent(FolderAdministration::rejectPlacement);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.CREATE, Ids.newId(),
                siblingKey(cabinetId, parentId, cleanName), null, new FolderPayload(cabinetId, parentId, cleanName),
                "Create folder " + pathOf(tree, parentId) + "/" + cleanName + " in cabinet " + cabinet.getCode()));
    }

    @Transactional
    public PendingChangeView proposeUpdate(UUID id, long expectedVersion, UUID newParentId, String newName) {
        Folder folder = loadAtVersion(id, expectedVersion);
        String cleanName = cleanName(newName);
        Map<UUID, Folder> tree = tree(folder.getCabinetId());
        placementProblem(tree, folder.getId(), newParentId, cleanName).ifPresent(FolderAdministration::rejectPlacement);
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.UPDATE, id,
                siblingKey(folder.getCabinetId(), newParentId, cleanName), expectedVersion,
                new FolderPayload(folder.getCabinetId(), newParentId, cleanName),
                "Move or rename folder " + folder.getPathCache() + " to " + pathOf(tree, newParentId) + "/"
                        + cleanName));
    }

    @Transactional
    public PendingChangeView proposeDelete(UUID id, long expectedVersion) {
        Folder folder = loadAtVersion(id, expectedVersion);
        if (folders.existsByParentId(id)) {
            throw new ApiException(HttpStatus.CONFLICT, FOLDER_NOT_EMPTY, "The folder has sub-folders.");
        }
        if (inUse(id)) {
            throw new ApiException(HttpStatus.CONFLICT, FOLDER_NOT_EMPTY, "The folder contains documents.");
        }
        return changeRequests.submit(new ChangeProposal(SUBJECT, ChangeOperation.DELETE, id, null, expectedVersion,
                new FolderPayload(folder.getCabinetId(), folder.getParentId(), folder.getName()),
                "Delete folder " + folder.getPathCache()));
    }

    @Override
    public String subjectType() {
        return SUBJECT;
    }

    @Override
    public Set<String> candidateRoles() {
        return Set.of(Roles.TENANT_ADMIN);
    }

    @Override
    public boolean canDecide(PendingChangeView change) {
        return support.canAdministerMetadata();
    }

    @Override
    public ApplyResult apply(PendingChangeView change) {
        FolderPayload payload = support.payload(change, FolderPayload.class);
        Cabinet cabinet = cabinets.findById(payload.cabinetId()).orElse(null);
        if (cabinet == null) {
            return ApplyResult.stale("The cabinet no longer exists.");
        }
        Map<UUID, Folder> tree = tree(payload.cabinetId());
        return switch (change.operation()) {
            case CREATE -> {
                Optional<String> problem = placementProblem(tree, null, payload.parentId(), payload.name());
                if (problem.isPresent()) {
                    yield ApplyResult.stale(problem.get());
                }
                Folder folder = folders.save(new Folder(change.subjectId(), payload.cabinetId(), payload.parentId(),
                        payload.name(), pathOf(tree, payload.parentId()) + "/" + payload.name()));
                support.auditApplied("FOLDER_CREATED", SUBJECT, folder.getId(), change,
                        Map.of("path", folder.getPathCache()));
                yield ApplyResult.ok();
            }
            case UPDATE -> {
                Folder folder = tree.get(change.subjectId());
                if (folder == null || folder.getVersionLock() != change.baseVersion()) {
                    yield ApplyResult.stale("The folder was changed after this change was proposed.");
                }
                Optional<String> problem = placementProblem(tree, folder.getId(), payload.parentId(), payload.name());
                if (problem.isPresent()) {
                    yield ApplyResult.stale(problem.get());
                }
                String before = folder.getPathCache();
                folder.moveTo(payload.parentId(), payload.name(), pathOf(tree, payload.parentId()) + "/"
                        + payload.name());
                repathDescendants(tree, folder);
                support.auditApplied("FOLDER_UPDATED", SUBJECT, folder.getId(), change,
                        Map.of("from", before, "to", folder.getPathCache()));
                yield ApplyResult.ok();
            }
            case DELETE -> {
                Folder folder = tree.get(change.subjectId());
                if (folder == null || folder.getVersionLock() != change.baseVersion()) {
                    yield ApplyResult.stale("The folder was changed after this change was proposed.");
                }
                if (folders.existsByParentId(folder.getId()) || inUse(folder.getId())) {
                    yield ApplyResult.stale("The folder now has sub-folders or documents.");
                }
                folders.delete(folder);
                support.auditApplied("FOLDER_DELETED", SUBJECT, folder.getId(), change,
                        Map.of("path", folder.getPathCache()));
                yield ApplyResult.ok();
            }
            case DEACTIVATE -> throw new IllegalStateException("Folders are deleted, not deactivated");
        };
    }

    private boolean inUse(UUID folderId) {
        return folderUsages.orderedStream().anyMatch(usage -> usage.isFolderInUse(folderId));
    }

    /** Why a folder (null when new) cannot have this parent and name, if it cannot. */
    private static Optional<String> placementProblem(Map<UUID, Folder> tree, UUID folderId, UUID parentId,
            String name) {
        if (parentId != null) {
            if (!tree.containsKey(parentId)) {
                return Optional.of("The parent folder does not exist in this cabinet.");
            }
            int depth = 1;
            for (UUID current = parentId; current != null; current = tree.get(current).getParentId()) {
                if (current.equals(folderId)) {
                    return Optional.of("A folder cannot be moved into itself or one of its sub-folders.");
                }
                depth++;
            }
            if (depth > MAX_DEPTH) {
                return Optional.of("Folders can be nested at most " + MAX_DEPTH + " levels deep.");
            }
        }
        boolean taken = tree.values().stream().anyMatch(other -> !other.getId().equals(folderId)
                && Objects.equals(other.getParentId(), parentId) && other.getName().equalsIgnoreCase(name));
        return taken ? Optional.of("A folder with this name already exists here.") : Optional.empty();
    }

    private static void rejectPlacement(String problem) {
        String code = problem.startsWith("A folder with this name") ? FOLDER_NAME_IN_USE : "FOLDER_PLACEMENT_INVALID";
        HttpStatus status = code.equals(FOLDER_NAME_IN_USE) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_CONTENT;
        throw new ApiException(status, code, problem);
    }

    private void repathDescendants(Map<UUID, Folder> tree, Folder moved) {
        tree.values().stream().filter(child -> moved.getId().equals(child.getParentId())).forEach(child -> {
            child.repath(moved.getPathCache() + "/" + child.getName());
            repathDescendants(tree, child);
        });
    }

    private static String pathOf(Map<UUID, Folder> tree, UUID folderId) {
        return folderId == null ? "" : tree.get(folderId).getPathCache();
    }

    private Map<UUID, Folder> tree(UUID cabinetId) {
        Map<UUID, Folder> tree = new HashMap<>();
        folders.findByCabinetId(cabinetId).forEach(folder -> tree.put(folder.getId(), folder));
        return tree;
    }

    private static String siblingKey(UUID cabinetId, UUID parentId, String name) {
        return (parentId == null ? cabinetId : parentId) + ":" + name.toLowerCase(Locale.ROOT);
    }

    private static String cleanName(String name) {
        String clean = name == null ? "" : name.trim();
        if (!NAME.matcher(clean).matches()) {
            List<Violation> violations = new ArrayList<>();
            violations.add(new Violation("name", "FOLDER_NAME_INVALID",
                    "Name is required, at most 200 characters, without / or \\ or control characters."));
            throw new ValidationFailedException("The folder is invalid.", violations);
        }
        return clean;
    }

    private Cabinet loadCabinet(UUID cabinetId) {
        return cabinets.findById(cabinetId).orElseThrow(() -> ApiException.notFound(
                CabinetAdministration.CABINET_NOT_FOUND, "Cabinet not found."));
    }

    private Folder load(UUID id) {
        return folders.findById(id).orElseThrow(() -> ApiException.notFound(FOLDER_NOT_FOUND, "Folder not found."));
    }

    private Folder loadAtVersion(UUID id, long expectedVersion) {
        Folder folder = load(id);
        if (folder.getVersionLock() != expectedVersion) {
            throw ApiException.staleVersion();
        }
        return folder;
    }
}
