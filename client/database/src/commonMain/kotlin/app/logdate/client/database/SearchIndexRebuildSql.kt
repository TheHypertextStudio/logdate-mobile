package app.logdate.client.database

/**
 * Version of the search index's content and triggers. Original ink documents share note storage,
 * but are never indexed as plain text.
 */
internal const val SEARCH_INDEX_SCHEMA_VERSION = 5L

internal const val SEARCH_SOURCE_ROWS_EXIST_SQL = """
    SELECT EXISTS(
        SELECT 1 FROM text_notes WHERE deletedAt IS NULL AND content_format = 'text'
        UNION ALL
        SELECT 1
        FROM transcriptions
        INNER JOIN audio_notes ON audio_notes.uid = transcriptions.noteId
        WHERE transcriptions.status = 'COMPLETED'
          AND transcriptions.text IS NOT NULL
          AND audio_notes.deletedAt IS NULL
        UNION ALL
        SELECT 1 FROM journals WHERE deletedAt IS NULL
        UNION ALL
        SELECT 1
        FROM media_captions
        LEFT JOIN image_notes ON image_notes.uid = media_captions.noteId
        LEFT JOIN video_notes ON video_notes.uid = media_captions.noteId
        WHERE COALESCE(image_notes.deletedAt, video_notes.deletedAt) IS NULL
          AND COALESCE(image_notes.uid, video_notes.uid) IS NOT NULL
        UNION ALL
        SELECT 1 FROM places WHERE deleted_at IS NULL
        UNION ALL
        SELECT 1 FROM rewinds WHERE title IS NOT NULL
        UNION ALL
        SELECT 1 FROM stickers WHERE label IS NOT NULL
        UNION ALL
        SELECT 1 FROM postcards WHERE title IS NOT NULL
        UNION ALL
        SELECT 1
        FROM audio_tags
        INNER JOIN audio_notes ON audio_notes.uid = audio_tags.noteId
        WHERE audio_notes.deletedAt IS NULL
    )
"""

internal const val INDEX_TEXT_NOTES_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT uid, content, created, 'text_note'
    FROM text_notes
    WHERE deletedAt IS NULL AND content_format = 'text'
"""

internal const val INDEX_TRANSCRIPTIONS_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT transcriptions.noteId, transcriptions.text, audio_notes.created, 'transcription'
    FROM transcriptions
    INNER JOIN audio_notes ON audio_notes.uid = transcriptions.noteId
    WHERE transcriptions.status = 'COMPLETED'
      AND transcriptions.text IS NOT NULL
      AND audio_notes.deletedAt IS NULL
"""

internal const val INDEX_JOURNALS_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT id, title || ' ' || COALESCE(description, ''), created, 'journal'
    FROM journals
    WHERE deletedAt IS NULL
"""

internal const val INDEX_MEDIA_CAPTIONS_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT media_captions.noteId,
           media_captions.caption,
           COALESCE(image_notes.created, video_notes.created),
           'media_caption'
    FROM media_captions
    LEFT JOIN image_notes ON image_notes.uid = media_captions.noteId
    LEFT JOIN video_notes ON video_notes.uid = media_captions.noteId
    WHERE COALESCE(image_notes.deletedAt, video_notes.deletedAt) IS NULL
      AND COALESCE(image_notes.uid, video_notes.uid) IS NOT NULL
"""

internal const val INDEX_PLACES_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT id, name || ' ' || COALESCE(description, ''), created, 'place'
    FROM places
    WHERE deleted_at IS NULL
"""

internal const val INDEX_REWINDS_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT uid, title, generationDate, 'rewind'
    FROM rewinds
    WHERE title IS NOT NULL
"""

internal const val INDEX_STICKERS_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT id, label, created_at, 'sticker'
    FROM stickers
    WHERE label IS NOT NULL
"""

internal const val INDEX_POSTCARDS_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT id, title, created_at, 'postcard'
    FROM postcards
    WHERE title IS NOT NULL
"""

internal const val INDEX_AMBIENT_SOUNDS_SQL = """
    INSERT INTO entries_fts(uid, content, created, contentType)
    SELECT audio_tags.noteId, audio_tags.soundName, audio_notes.created, 'ambient_sound'
    FROM audio_tags
    INNER JOIN audio_notes ON audio_notes.uid = audio_tags.noteId
    WHERE audio_notes.deletedAt IS NULL
"""
