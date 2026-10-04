package com.kksg.applicationServices.repository.mapper;

import com.kksg.applicationServices.repository.dto.RepositoryOwner;
import com.kksg.applicationServices.repository.dto.RepositoryRefResponse;
import com.kksg.applicationServices.repository.dto.RepositoryResponse;
import com.kksg.applicationServices.repository.dto.RepositoryVisibility;
import com.kksg.applicationServices.repository.dto.ScmResourceProvider;
import com.kksg.applicationServices.repository.service.RepositoryRef;
import com.kksg.applicationServices.scm.common.model.NormalizedRepository;
import com.kksg.applicationServices.scm.common.util.ScmInstants;

import java.util.Locale;

/**
 * Normalized repository to response DTO, following the project's static-mapper convention.
 *
 * <p>No provider is named and no provider-shaped field is read: by the time a value arrives here the
 * engine's declarative response mapping has already turned {@code full_name} and {@code mainbranch.name}
 * into the same two normalized fields. That is the whole reason this class can be twenty lines rather
 * than a per-provider hierarchy.
 */
public final class RepositoryMapper {

    private RepositoryMapper() {
    }

    public static RepositoryResponse toResponse(NormalizedRepository repository,
                                                ScmResourceProvider provider) {
        return RepositoryResponse.builder()
                .id(repository.getExternalId())
                .name(repository.getName())
                .fullName(resolveFullName(repository))
                .description(blankToNull(repository.getDescription()))
                .defaultBranch(blankToNull(repository.getDefaultBranch()))
                .visibility(RepositoryVisibility.fromIsPrivate(repository.getIsPrivate()))
                .webUrl(blankToNull(repository.getWebUrl()))
                .cloneUrl(blankToNull(repository.getCloneUrl()))
                .owner(toOwner(repository))
                .provider(provider)
                .updatedAt(ScmInstants.parse(repository.getUpdatedAt()))
                .build();
    }

    /**
     * The repository reference carried on a pull-request detail response.
     *
     * <p>Built from the request's own {@link RepositoryRef} rather than from a fetched repository, so it
     * costs no extra provider call and is guaranteed to agree with the path the client used.
     */
    public static RepositoryRefResponse toRef(RepositoryRef ref, ScmResourceProvider provider) {
        return new RepositoryRefResponse(ref.name(), ref.fullName(), ref.owner(), provider);
    }

    /**
     * Guarantees a usable {@code fullName}.
     *
     * <p>It is the field clients build nested links from, so an absent one would be a dead end. A
     * provider that omits it on a reduced payload still supplies owner and name, so the value is
     * reconstructed rather than left null.
     */
    private static String resolveFullName(NormalizedRepository repository) {
        String fullName = blankToNull(repository.getFullName());
        if (fullName != null) {
            return fullName;
        }
        String owner = blankToNull(repository.getOwner());
        String name = blankToNull(repository.getName());
        return owner != null && name != null ? owner + "/" + name : name;
    }

    private static RepositoryOwner toOwner(NormalizedRepository repository) {
        String name = blankToNull(repository.getOwner());
        String id = blankToNull(repository.getOwnerExternalId());
        String avatarUrl = blankToNull(repository.getOwnerAvatarUrl());

        // An owner object with every field null would be noise in the response; omit it instead.
        return name == null && id == null && avatarUrl == null
                ? null
                : new RepositoryOwner(id, name, avatarUrl);
    }

    /**
     * Whether a repository matches a search term.
     *
     * <p>Lives here rather than in the scanner because which fields are searchable is a property of the
     * resource. Name, owner-qualified name and description are covered: a user searching "auth" means
     * any of "the repository called auth-service", "the one under the auth org", or "the one whose
     * description mentions auth".
     *
     * <p>The term arrives already lower-cased from {@code PageQuery}, so only the candidate values are
     * folded here - folding both sides per comparison would repeat that work once per scanned item.
     */
    public static boolean matches(NormalizedRepository repository, String lowerCaseTerm) {
        return containsIgnoringCase(repository.getName(), lowerCaseTerm)
                || containsIgnoringCase(repository.getFullName(), lowerCaseTerm)
                || containsIgnoringCase(repository.getDescription(), lowerCaseTerm);
    }

    private static boolean containsIgnoringCase(String candidate, String lowerCaseTerm) {
        return candidate != null && candidate.toLowerCase(Locale.ROOT).contains(lowerCaseTerm);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
