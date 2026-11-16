----------------------------- MODULE BackupSync -----------------------------
EXTENDS Naturals, FiniteSets, TLC
CONSTANTS MaxGeneration,
          CellularAllowed,
          BuggyDraftEcho,
          BuggyCheckingTitle,
          ReconcileKnownCommit,
          MediaWork,
          BuggyCountDeferredRows,
          PlatformRequiresForeground,
          BuggyQuietFallback,
          OriginalAlreadyUploaded
VARIABLES owner,
          transfers,
          generation,
          acknowledged,
          committed,
          phase,
          active,
          waiters,
          network,
          charging,
          authenticated,
          keys,
          serverUp,
          notice,
          completed,
          total,
          falsePending,
          replayedAcknowledged,
          replayedCommitted,
          protectingWork
vars ==
  << owner,
     transfers,
     generation,
     acknowledged,
     committed,
     phase,
     active,
     waiters,
     network,
     charging,
     authenticated,
     keys,
     serverUp,
     notice,
     completed,
     total,
     falsePending,
     replayedAcknowledged,
     replayedCommitted,
     protectingWork
  >>
NetworkAllowed ==
  network = "wifi" \/ ( CellularAllowed /\ network = "cellular" )
Ready == authenticated /\ keys /\ NetworkAllowed
Healthy == Ready /\ serverUp
TransferHealthy ==
  Healthy /\ ( ~MediaWork \/ OriginalAlreadyUploaded \/ network = "wifi" )
Pending == generation > acknowledged \/ falsePending
Init ==
  /\ generation = 0 /\ acknowledged = 0 /\ committed = 0
  /\ phase = "idle" /\ active = 0 /\ waiters = { 1, 2 } /\ owner = 0 /\
       transfers = {}
  /\ network = "offline" /\ charging = FALSE
  /\ authenticated = FALSE /\ keys = FALSE /\ serverUp = FALSE
  /\ notice = "off" /\ completed = 0 /\ total = 0
  /\ protectingWork = FALSE
  /\ falsePending = FALSE /\ replayedAcknowledged = FALSE /\
       replayedCommitted = FALSE
Edit ==
  /\ generation < MaxGeneration
  /\ generation' = generation + 1 /\ waiters' = waiters \cup { 1, 2 }
  /\ UNCHANGED << owner,
        transfers,
        acknowledged,
        committed,
        phase,
        active,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        notice,
        completed,
        total,
        falsePending,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
Environment ==
  /\ network' \in { "offline", "cellular", "wifi" }
  /\ charging' \in BOOLEAN /\ authenticated' \in BOOLEAN
  /\ keys' \in BOOLEAN /\ serverUp' \in BOOLEAN
  /\ UNCHANGED << owner,
        transfers,
        generation,
        acknowledged,
        committed,
        phase,
        active,
        waiters,
        notice,
        completed,
        total,
        falsePending,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
\* Periodic work and concurrent manual/network triggers coalesce, not a second worker.
Wake ==
  /\ waiters # { 1, 2 } /\ waiters' = waiters \cup { 1, 2 }
  /\ UNCHANGED << owner,
        transfers,
        generation,
        acknowledged,
        committed,
        phase,
        active,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        notice,
        completed,
        total,
        falsePending,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
BeginCheck ==
  /\ phase = "idle" /\ owner = 0 /\ waiters # {} /\ Ready
  /\ phase' = "checking"
  /\ \E worker \in waiters: owner' = worker /\ waiters' = waiters \ { worker }
  /\ active' = 0 /\ notice' = "off" /\ completed' = 0 /\ total' = 0
  /\ protectingWork' = FALSE
  /\ UNCHANGED << transfers,
        generation,
        acknowledged,
        committed,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        falsePending,
        replayedAcknowledged,
        replayedCommitted
     >>
LongCheck ==
  /\ phase = "checking" /\ notice = "off"
  /\ ( PlatformRequiresForeground \/ BuggyQuietFallback \/
         ( Ready /\ Pending /\
             ( ~MediaWork \/ OriginalAlreadyUploaded \/ network = "wifi" )
         )
     )
  /\ notice' = IF BuggyCheckingTitle THEN "backing" ELSE "checking"
  /\ protectingWork' =
       ( protectingWork \/
           ( Ready /\ Pending /\
               ( ~MediaWork \/ OriginalAlreadyUploaded \/ network = "wifi" )
           )
       )
  /\ UNCHANGED << owner,
        transfers,
        generation,
        acknowledged,
        committed,
        phase,
        active,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        completed,
        total,
        falsePending,
        replayedAcknowledged,
        replayedCommitted
     >>
\* SyncRunPhases counts a due row before NoteMediaUpload defers its original on cellular.
\* This announces 0/1 without starting a payload or acknowledging the retained operation.
DeclareDeferred ==
  /\ BuggyCountDeferredRows /\ MediaWork /\ phase = "checking"
  /\ Healthy /\ Pending /\ network = "cellular" /\ notice # "backing"
  /\ notice' = "backing" /\ total' = 1 /\ completed' = 0
  /\ UNCHANGED << owner,
        transfers,
        generation,
        acknowledged,
        committed,
        phase,
        active,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        falsePending,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
\* Authenticated receipt lookup or exact rich-draft equality is metadata, not payload replay.
Receipt ==
  /\ ReconcileKnownCommit /\ phase = "checking" /\ Healthy /\
       committed > acknowledged
  /\ acknowledged' = committed /\ falsePending' = FALSE
  /\ UNCHANGED << owner,
        transfers,
        generation,
        committed,
        phase,
        active,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        notice,
        completed,
        total,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
Upload ==
  /\ phase = "checking" /\ Healthy /\ Pending
  /\ ( ~MediaWork \/ OriginalAlreadyUploaded \/ network = "wifi" )
  /\ ( committed <= acknowledged \/ ~ReconcileKnownCommit )
  /\ phase' = "uploading" /\ active' = generation
  /\ transfers' = transfers \cup { owner }
  /\ notice' = "backing" /\ total' = 1 /\ completed' = 0
  /\ replayedAcknowledged' =
       ( replayedAcknowledged \/ generation <= acknowledged )
  /\ replayedCommitted' = ( replayedCommitted \/ generation <= committed )
  /\ protectingWork' = TRUE
  /\ UNCHANGED << owner,
        generation,
        acknowledged,
        committed,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        falsePending
     >>
Commit ==
  /\ phase = "uploading" /\ Healthy
  /\ committed' = active /\ phase' = "awaitingAck"
  /\ UNCHANGED << owner,
        transfers,
        generation,
        acknowledged,
        active,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        notice,
        completed,
        total,
        falsePending,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
Acknowledge ==
  /\ phase = "awaitingAck" /\ Healthy
  /\ acknowledged' = active /\ completed' = 1
  /\ phase' = "idle" /\ active' = 0 /\ notice' = "off"
  /\ falsePending' = FALSE
  /\ owner' = 0 /\ transfers' = {}
  /\ protectingWork' = FALSE
  /\ UNCHANGED << generation,
        committed,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        total,
        replayedAcknowledged,
        replayedCommitted
     >>
\* Failure/lost response preserves the durable server commit; backoff never deletes pending work.
Fail ==
  /\ phase \in { "checking", "uploading", "awaitingAck" }
  /\ ( ~Healthy \/ phase = "awaitingAck" )
  /\ phase' = "backoff" /\ active' = 0 /\ notice' = "off"
  /\ completed' = 0 /\ total' = 0 /\ waiters' = waiters \cup { 1, 2 }
  /\ owner' = 0 /\ transfers' = {} /\ protectingWork' = FALSE /\
       UNCHANGED << generation,
          acknowledged,
          committed,
          network,
          charging,
          authenticated,
          keys,
          serverUp,
          falsePending,
          replayedAcknowledged,
          replayedCommitted
       >>
BackoffExpires ==
  /\ phase = "backoff" /\ phase' = "idle"
  /\ UNCHANGED << owner,
        transfers,
        generation,
        acknowledged,
        committed,
        active,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        notice,
        completed,
        total,
        falsePending,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
FinishCheck ==
  /\ phase = "checking" /\ Healthy /\ ~Pending
  /\ committed <= acknowledged /\ phase' = "idle" /\ notice' = "off"
  /\ owner' = 0 /\ transfers' = {}
  /\ protectingWork' = FALSE
  /\ UNCHANGED << generation,
        acknowledged,
        committed,
        active,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        completed,
        total,
        falsePending,
        replayedAcknowledged,
        replayedCommitted
     >>
\* Restart/cancellation may interrupt transient work; generations and receipts survive.
Interrupt ==
  /\ phase # "idle"
  /\ phase' = "idle" /\ active' = 0 /\ notice' = "off"
  /\ completed' = 0 /\ total' = 0 /\ waiters' = waiters \cup { 1, 2 }
  /\ owner' = 0 /\ transfers' = {} /\ protectingWork' = FALSE /\
       UNCHANGED << generation,
          acknowledged,
          committed,
          network,
          charging,
          authenticated,
          keys,
          serverUp,
          falsePending,
          replayedAcknowledged,
          replayedCommitted
       >>
\* Counterexample switch corresponding to DraftDownloader's old precision comparison.
Echo ==
  /\ BuggyDraftEcho /\ phase = "checking" /\ generation = acknowledged
  /\ generation > 0 /\ ~falsePending /\ falsePending' = TRUE
  /\ UNCHANGED << owner,
        transfers,
        generation,
        acknowledged,
        committed,
        phase,
        active,
        waiters,
        network,
        charging,
        authenticated,
        keys,
        serverUp,
        notice,
        completed,
        total,
        replayedAcknowledged,
        replayedCommitted,
        protectingWork
     >>
Next ==
  Edit \/ Environment \/ Wake \/ BeginCheck \/ LongCheck \/ DeclareDeferred \/
                    Receipt \/
                  Upload \/
                Commit \/
              Acknowledge \/
            Fail \/
          BackoffExpires \/
        FinishCheck \/
      Interrupt \/
    Echo
Spec == Init /\ [][Next]_vars
TypeOK ==
  /\ generation \in 0 .. MaxGeneration /\ acknowledged \in 0 .. generation
  /\ committed \in acknowledged .. generation /\ active \in 0 .. generation
  /\ phase \in { "idle", "checking", "uploading", "awaitingAck", "backoff" }
  /\ notice \in { "off", "checking", "backing" }
  /\ waiters \subseteq { 1, 2 } /\ owner \in 0 .. 2 /\
         transfers \subseteq { 1, 2 } /\
       network \in { "offline", "cellular", "wifi" }
  /\ charging \in BOOLEAN /\ authenticated \in BOOLEAN /\ keys \in BOOLEAN
  /\ serverUp \in BOOLEAN /\ falsePending \in BOOLEAN
  /\ replayedAcknowledged \in BOOLEAN /\ replayedCommitted \in BOOLEAN
  /\ completed \in 0 .. total /\ total \in 0 .. 1 /\ protectingWork \in BOOLEAN
NoAcknowledgedPayload == ~replayedAcknowledged
NoDiffNoLive ==
  ( generation = acknowledged /\ committed <= acknowledged ) =>
    notice # "backing"
NoCommittedPayloadReplay == ~replayedCommitted
OneTransfer ==
  /\ Cardinality(transfers) <= 1
  /\ ( ( phase \in { "uploading", "awaitingAck" } ) <=> active > 0 )
  /\ ( active > 0 => transfers = { owner } /\ owner # 0 )
  /\ ( ( phase \in { "idle", "backoff" } ) <=> owner = 0 )
TruthfulNotification ==
  notice = "backing" => phase \in { "uploading", "awaitingAck" } /\ total > 0
\* FGS compliance is separate from a promoted backup claim. Required ordinary notices remain allowed.
NoUnnecessaryCheckNotice ==
  ( phase = "checking" /\ ~Pending /\ ~PlatformRequiresForeground /\
        ~protectingWork
    ) =>
    notice # "checking"
NoDeferredLiveClaim ==
  ( phase = "checking" /\ MediaWork /\ ~OriginalAlreadyUploaded /\
        network = "cellular"
    ) =>
    notice # "backing"
\* SF completion excludes perpetual OS/process preemption; this is an explicit assumption, not an Android guarantee.
FairSpec ==
  Spec /\ SF_vars(BeginCheck) /\ SF_vars(Receipt) /\ SF_vars(Upload) /\
          SF_vars(Commit) /\
        SF_vars(Acknowledge) /\
      SF_vars(BackoffExpires) /\
    WF_vars(Wake)
EventuallyBackedUp ==
  ( <>[]TransferHealthy ) =>
    ( generation > acknowledged ~> generation = acknowledged )
PluggedWifiBacksUp ==
  ( <>[]( network = "wifi" /\ charging /\ authenticated /\ keys /\ serverUp ) ) =>
    ( generation > acknowledged ~> generation = acknowledged )
=============================================================================

