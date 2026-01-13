package com.calai.backend.foodlog.repo;

import com.calai.backend.foodlog.entity.ImageBlobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface ImageBlobRepository extends JpaRepository<ImageBlobEntity, Long> {

    Optional<ImageBlobEntity> findByUserIdAndSha256(Long userId, String sha256);

    /** 新 blob：INSERT IGNORE，成功回 1 */
    @Modifying
    @Query(
            value = """
            INSERT IGNORE INTO image_blobs(user_id, sha256, object_key, content_type, size_bytes, ext, ref_count, created_at_utc, updated_at_utc)
            VALUES (:userId, :sha256, :objectKey, :contentType, :sizeBytes, :ext, 1, :now, :now)
            """,
            nativeQuery = true
    )
    int insertFirst(@Param("userId") Long userId,
                    @Param("sha256") String sha256,
                    @Param("objectKey") String objectKey,
                    @Param("contentType") String contentType,
                    @Param("sizeBytes") long sizeBytes,
                    @Param("ext") String ext,
                    @Param("now") Instant now);

    /** 既有 blob：ref_count + 1 */
    @Modifying
    @Query(
            value = """
            UPDATE image_blobs
            SET ref_count = ref_count + 1,
                updated_at_utc = :now
            WHERE user_id = :userId AND sha256 = :sha256
            """,
            nativeQuery = true
    )
    int retain(@Param("userId") Long userId, @Param("sha256") String sha256, @Param("now") Instant now);

    /** release：ref_count - 1（不讓它變負） */
    @Modifying
    @Query(
            value = """
            UPDATE image_blobs
            SET ref_count = CASE WHEN ref_count > 0 THEN ref_count - 1 ELSE 0 END,
                updated_at_utc = :now
            WHERE user_id = :userId AND sha256 = :sha256
            """,
            nativeQuery = true
    )
    int release(@Param("userId") Long userId, @Param("sha256") String sha256, @Param("now") Instant now);

    @Query(
            value = """
            SELECT ref_count FROM image_blobs
            WHERE user_id = :userId AND sha256 = :sha256
            """,
            nativeQuery = true
    )
    Integer getRefCount(@Param("userId") Long userId, @Param("sha256") String sha256);

    @Modifying
    @Query(
            value = """
            DELETE FROM image_blobs
            WHERE user_id = :userId AND sha256 = :sha256 AND ref_count <= 0
            """,
            nativeQuery = true
    )
    int deleteIfZero(@Param("userId") Long userId, @Param("sha256") String sha256);
}
