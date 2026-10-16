# Finish the active quest steps

`/finishquest` skips currently active objectives in one quest chain, not every quest in the journal. With multiple active chains it lists their parent IDs without changing anything; choose one with `/finishquest <parent ID>`. Inactive and finished chains are refused. Permissions remain `player.quest` for self-use and `player.quest.others` when targeting another player.

The command runs on the quest event executor, snapshots the selected parent's active steps and calls their normal `GameQuest.finish()` hooks in quest order. Only a real final-step hook can close the parent and award its completion reward. It never calls `GameMainQuest.finish()` to force closure. If finishing a step starts another required objective, that objective stays active; repeat `/finishquest` to skip it. Natural parent completion stops the snapshot immediately, leaving optional objectives and successor chains untouched.

This is a quest skip, not normal gameplay. Unstarted objectives, scene interactions and arbitrary quest scripts are not replayed. A cancelled hook or script exception stops completion; inspect the state before retrying. There is no automatic per-use save backup or general undo command.

## Regression

The previous implementation closed parent 358 after skipping step 35801, while the newly unlocked final step 35802 remained unfinished. The temple chains 306/307/308 require that step, so they never unlocked. Tests reproduce both immediate and queued same-parent unlocks, natural final-step completion, reward-once behavior, ordering, cancellation and unrelated/successor preservation.

```bash
bash gradlew test --tests '*CurrentQuestCompletionTest' --offline --no-daemon
```

The existing account needs a one-time normal completion of 35802, preserving the already-finished parent and its paid reward. Verify successor startup separately; changing the command cannot repair an already-persisted premature completion.

## Deployment

Deploy only `CurrentQuestCompletion.class` and `FinishQuestCommand.class`; the existing explicit command registration remains unchanged. Retain the installation backup, entry hashes and player-repair evidence. Restore only changed archive entries to roll back code; an archive rollback does not undo player quest progress. Persistent deployment and repair changes belong in `/home/aim/SYSTEM_CHANGES.md`.
