package app.logdate.client.database

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Module for providing data access objects for the client.
 */
val daosModule: Module =
    module {
        single { get<LogDateDatabase>().textNoteDao() }
        single { get<LogDateDatabase>().imageNoteDao() }
        single { get<LogDateDatabase>().videoNoteDao() }
        single { get<LogDateDatabase>().audioNoteDao() }
        single { get<LogDateDatabase>().journalDao() }
        single { get<LogDateDatabase>().journalNotesDao() }
        single { get<LogDateDatabase>().journalContentDao() }
        single { get<LogDateDatabase>().rewindDao() }
        single { get<LogDateDatabase>().rewindGenerationRequestDao() }
        single { get<LogDateDatabase>().locationHistoryDao() }
        single { get<LogDateDatabase>().historyRecordDao() }
        single { get<LogDateDatabase>().historyOwnerAdoptionDao() }
        single { get<LogDateDatabase>().locationActivityDao() }
        single { get<LogDateDatabase>().storageMetadataDao() }
        single { get<LogDateDatabase>().userDevicesDao() }
        single { get<LogDateDatabase>().userMediaDao() }
        single { get<LogDateDatabase>().indexedMediaDao() }
        single { get<LogDateDatabase>().transcriptionDao() }
        single { get<LogDateDatabase>().searchDao() }
        single { get<LogDateDatabase>().searchDetailsDao() }
        single { get<LogDateDatabase>().syncMetadataDao() }
        single { get<LogDateDatabase>().peerDeliveryDao() }
        single { get<LogDateDatabase>().markedMomentDao() }
        single { get<LogDateDatabase>().integrityDao() }
        single { get<LogDateDatabase>().placeDao() }
        single { get<LogDateDatabase>().userPlaceDao() }
        single { get<LogDateDatabase>().mediaCaptionDao() }
        single { get<LogDateDatabase>().mediaExifDao() }
        single { get<LogDateDatabase>().healthSnapshotDao() }
        single { get<LogDateDatabase>().postcardDao() }
        single { get<LogDateDatabase>().stickerDao() }
        single { get<LogDateDatabase>().eventDao() }
        single { get<LogDateDatabase>().eventNoteLinkDao() }
        single { get<LogDateDatabase>().audioTagDao() }
        single { get<LogDateDatabase>().reflectionPromptResponseDao() }
        single { get<LogDateDatabase>().personDao() }
        single { get<LogDateDatabase>().inferredPersonClusterDao() }
        single { get<LogDateDatabase>().inferredPersonEvidenceDao() }
        single { get<LogDateDatabase>().personLinkDao() }
        single { get<LogDateDatabase>().personResolutionDecisionDao() }
    }
