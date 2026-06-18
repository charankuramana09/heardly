package com.otter.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SearchService {

    @PersistenceContext
    private EntityManager em;

    public record SearchHit(
        UUID recordingId,
        String filename,
        Instant createdAt,
        String snippet,
        boolean matchedTranscript
    ) {}

    @Transactional(readOnly = true)
    @SuppressWarnings("unchecked")
    public List<SearchHit> search(UUID userId, String rawQuery) {
        if (rawQuery == null) return List.of();
        String q = rawQuery.trim();
        if (q.isEmpty()) return List.of();

        Query query = em.createNativeQuery("""
            SELECT
                r.id::text AS recording_id,
                r.original_filename,
                r.created_at,
                COALESCE(
                    NULLIF(
                        ts_headline(
                            'english',
                            t.full_text,
                            plainto_tsquery('english', :q),
                            'StartSel=⟪, StopSel=⟫, MaxFragments=1, MaxWords=24, MinWords=8, ShortWord=2'
                        ),
                        ''
                    ),
                    SUBSTRING(COALESCE(t.full_text, '') FROM 1 FOR 160)
                ) AS snippet,
                CASE WHEN t.full_text IS NOT NULL
                          AND to_tsvector('english', t.full_text) @@ plainto_tsquery('english', :q)
                     THEN TRUE ELSE FALSE END AS matched_transcript,
                COALESCE(
                    ts_rank(to_tsvector('english', t.full_text), plainto_tsquery('english', :q)),
                    0
                ) AS rank
            FROM recordings r
            LEFT JOIN transcripts t ON t.recording_id = r.id
            WHERE r.user_id = :userId
              AND (
                  (t.full_text IS NOT NULL AND to_tsvector('english', t.full_text) @@ plainto_tsquery('english', :q))
                  OR LOWER(COALESCE(r.original_filename, '')) LIKE LOWER(:like)
              )
            ORDER BY matched_transcript DESC, rank DESC, r.created_at DESC
            LIMIT 20
            """);
        query.setParameter("q", q);
        query.setParameter("userId", userId);
        query.setParameter("like", "%" + q + "%");

        List<Object[]> rows = query.getResultList();
        return rows.stream().map(r -> new SearchHit(
            UUID.fromString((String) r[0]),
            (String) r[1],
            toInstant(r[2]),
            (String) r[3],
            (Boolean) r[4]
        )).toList();
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (v instanceof java.time.LocalDateTime ldt) return ldt.atZone(java.time.ZoneOffset.UTC).toInstant();
        if (v instanceof java.util.Date d) return d.toInstant();
        return null;
    }
}
