# Finding Log

## 2026-06-20 - Remaining LOW-severity fixes (1.0.7)

Hypothesis: the 10 LOW items from the audit are worth fixing too.
Finding: **True** — 9 fixed (the 10th, NDSRom.copy EOF, was already fixed in the 1.0.6 batch). 4 files, central clean build + dup-check + headless-load + GUI-boot smoke test all green.
Fixes:
- SpeciesBaseStatRandomizer: new achievableMinBST(Species) helper (Shedinja HP=1 -> 51, else 70); applyCeiling now floors at the real achievable minimum instead of 6 so low gate targets are honored (no silent HP-clamp inflation); capStageBelowEvolution skips when strictly-below is unachievable (evoBST <= minSum) instead of leaving from >= evo. (#24/#25; #25's floor also removes most #24 triggers.)
- RandomizerGUI: attemptToLogException System.err redirect now synchronized on a static lock (#26); batch SwingWorker setCursor moved onto the EDT (#30); loadQS/saveQS, the update-check BufferedReader+HttpURLConnection, and saveLogFile all converted to try-with-resources / finally (#29/#31/#32).
- AbstractGBRomHandler.saveRomFile: try-with-resources + delete partial file on failure + null-message guard (#28).
- TypeEffectivenessEditorDialog.loadTypeIcons: icon resource streams opened in try-with-resources with null guard (#33).
Reason: mechanical leak/EDT fixes + the two BST-floor edge cases; verified by build + smoke tests.
Next Step: none — all 32 actionable audit findings (10 high + 13 med + 9 low; 1 low pre-fixed) resolved; the only non-fix was the #8 Gen7 false positive.


## 2026-06-20 - Multi-agent bug audit + 22 High/Medium fixes (1.0.6)

Hypothesis: a broad multi-agent audit (find + adversarial verify) would surface real bugs in recent work + high-risk areas.
Finding: **True.** 12 finders -> 39 raw -> 33 confirmed (10 high, 13 med, 10 low) after adversarial verification (6 false positives filtered, e.g. a "Gen1 BST omits Special" claim that missed the Gen1Species.getBST() override). Fixed all High+Medium (23 targeted; 22 real after one more false positive caught at fix time).
Key fixes (one agent per file, then central clean build + dup-check + headless ROM-load + GUI-boot smoke test, all green):
- REGRESSIONS from this session: preset/settings-string load broken by the 5-byte gate block (RandomizerGUI.getValidRequiredROMName: != -> <); WHOLE_LINE BST gate cancelled itself out + rewrote non-qualifying lines (SpeciesBaseStatRandomizer.gateWholeLine rewritten to per-stage-gate qualifying lines only).
- Editor data-corruption: searchable combos committed first filtered match not typed text (EditorUtils.commitSelection prefer findExactMatch); wild/static species edits dropped/corrupted forme on Gen6/7 (Encounters/StaticEncountersEditorPanel now set forme + reduce to base); Gen6/7 egg-move + Gen3/4/5/6 learnset sheets wiped other panels' shared-map edits on reload/close (ported Gen5's touchedKeys revert); Gen1 "Reset to default" never restored Special (PokemonCardViewPanel gen-aware stat index/snapshot).
- Level curve: Gen7 ELITE milestone-key collision (n+4 -> non-overlapping keyspace); low-ace evil-team admin became Scale/Even anchor (anchor from first progression milestone).
- Settings migration: 3 bit bugs in SettingsUpdater (0x80>>2 precedence; |->& x2 for updateTypeEffectiveness).
- Robustness: Gen6 readTypeTable null-effectiveness crash (throw descriptive + mask 0xFF); off-EDT modal dialogs (RandomizerGUI failure branch + attemptToLogException via runOnEDT helper); finishRandomization left handler unreloaded on log-save failure (fall through to cleanup); NDSRom.saveTo partial-corrupt-output + handle leak (try-with-resources + delete partial; copy() EOF check); BW2 fairy-table 0x1740 unconditional early-return removed (validated candidate).
FALSE POSITIVE caught at fix time: #8 "Gen7 type editor enabled but silently discards" - WRONG. Abstract3DSRomHandler extends AbstractRomHandler (NOT AbstractDSRomHandler); AbstractRomHandler.hasTypeEffectivenessSupport() returns false; Gen7 doesn't override -> editor already gated OFF for Gen7. Verifier mis-traced the hierarchy. No change needed.
Reason: each fix reused existing helpers/patterns; verified by build + smoke tests.
Next Step: 10 LOW-severity items still open (stream-leak-on-exception cleanups, setCursor off EDT, System.err redirect race, KEEP_BELOW_EVO/HP-floor BST edge cases) - deferred.


## 2026-06-20 - De-gate Gen5 Fairy detection (signature fallback) (1.0.5)

Hypothesis: the Fairy signature search can be made to auto-detect Gen5 Fairy hacks that don't rename the type-name text, ADDED to (not replacing) the existing name check.
Finding: **True — implemented and verified safe.**
Evidence/Reason:
- Previously `readTypeTable()` only ran the signature search if `detectFairyType()` (type-name text contains "Fairy") returned true → a hack that adds Fairy mechanically (type byte 0x11) without renaming type names was missed.
- Added `detectFairyTypeBySignature()` + `scanForFairySignature()`: fallback runs ONLY when the name check is false; does exact known-table match (`findSignatureFairyTableOffset`) + Fairy's strict interaction fingerprint (`checkFairyAttackingRow`+`checkFairyDefendingColumn`) at standard candidate offsets only (0x0, TypeEffectivenessOffset, DEFAULT_BW2_FAIRY_TABLE_OFFSET). Deliberately NOT the 10-30s full `findTableByFairyInteractions` sweep (would penalize every vanilla load). Wired into all 3 detection call sites (loadPokemonStats/loadMoves/readTypeTable), name check first. Cached (`fairySignatureDetected`), read-failures retry, reset in `loadedROM()`.
- CRITICAL safety check (no false-positive on vanilla): headless probe (`loadRom`) on vanilla Pokemon Black AND user's 1.nds → both `detectFairyType=false`, `detectFairyTypeBySignature=false`, ROM loads as 17-type. Builds clean, no dup methods.
- Limitation: positive detection is sound by reuse of the existing proven signature matchers but was NOT run against a live Fairy hack (none on disk); a non-standard-offset custom-table hack that also omits the name rename would still be missed (only exact-known-table is full-buffer; interaction check is candidate-offsets-only, by design for speed).
Next Step: none — shipped 1.0.5. (If a Fairy hack with a custom table at a non-standard offset ever fails to detect, widen scanForFairySignature's candidate set or gate a full sweep behind a cheap type-byte precheck.)


## 2026-06-20 - Gen5 Fairy detect-once + self-contained launcher (1.0.3, 1.0.4)

Hypothesis: the Fairy subsystem broke (detectFairyType printed `false` ~3-4x); separately, the bundled JRE was incomplete.
Finding: **Mixed — both explained, neither was a regression I caused.**
Evidence:
- `detectFairyType()` returning `false` is CORRECT for the loaded vanilla BW/BW2 ROM (17 types, no "Fairy"; Fairy is Gen6+). Subsystem intact (string gate + `findTableByFairyInteractions` signature search + `matchesB2W2FairyTable` + table builders). The signature search is gated behind `detectFairyType()` being true, so a Fairy-hack that doesn't rename type-name text would bypass it (latent, not hit here).
- Ran 3x because all 3 call sites (`loadPokemonStats`:266, `loadMoves`:318, `readTypeTable`:3245) guard with `if(!hasFairyType)`, which only cached a POSITIVE result; `false` was recomputed each time. Call sites original since first commit `6f3dc36` (not added by me).
- Bundled `launcher/jre/` had `lib/` but no `bin/` (java.exe). Root cause: `.gitignore` `[Bb]in/` (line 36) silently matches the JRE's own `bin/` → never committed. So `launcher_WINDOWS.bat` fell back to system Java every release since 1.0.0.
Fixes:
- Added `Boolean fairyTypeDetected` cache: detection runs once per ROM (caches definitive yes/no); read-failures stay un-cached so they retry (robustness preserved). Reset in `loadedROM()`. Restored `upr.debugTypeTable` default to true. → release 1.0.3.
- Bundled complete Temurin 8u462 JRE `bin/` (SHA-256 verified, smoke-tested boots the app), scoped `.gitignore` un-ignore `!launcher/jre/bin/**`, `git add -f`. `.bat` untouched. → release 1.0.4 (now runs with no system Java).
Next Step: none — shipped. (Optional future: de-gate the Fairy signature search so Fairy-hacks that don't rename type text still detect.)


## 2026-06-19 - POTENTIAL_BUGS editor/misc fixes #7 #8 #9 #35

Hypothesis: Four POTENTIAL_BUGS editor/misc claims (#7 TM/tutor dialogs bypass save/revert lifecycle, #8 TrainerEditorPanel hard-codes text limits, #9 Gen5 DEBUG_TYPE_TABLE defaults true, #35 moves-sheet wrapper tables show stale derived values) are accurate against current code.

Finding: True (all four verified, all fixed).
- #9: `Gen5RomHandler.java:87` was `System.getProperty("upr.debugTypeTable", "true")` -> changed default to `"false"`. Property name confirmed; flag gates 7 stdout sites (L2650-2768).
- #8: `TrainerEditorPanel` had `MAX_NAME_LEN=MAX_CLASS_LEN=12` (static). `RomHandler` exposes `maxTrainerNameLength()` (L454) + `maxTrainerClassNameLength()` (L473). Replaced with instance fields `maxNameLen`/`maxClassLen` resolved in ctor from the handler (fallback DEFAULT_TEXT_LEN=12 when handler returns <=0, wrapped in try/catch). DocumentFilter (MaxLengthFilter) + tooltips + class-name dialog truncation/hint all now use them. trainerNameMode()/maxSumOfTrainerNameLengths() (Gen2 combined-length) NOT addressed — deeper, out of the stated core ask.
- #7: `EditorUtils.editTMMoves/editMoveTutorMoves` -> `showMoveAssignmentDialog` calls the setter (setTMMoves/setMoveTutorMoves) IMMEDIATELY on OK; the 6 panels' reload()/onWindowClosing() only restored the compatibility matrix, never the TM/tutor move list. Chose the smaller safe option (per instructions): added `EditorUtils.MoveListGuard` (snapshot at ctor, markDirty on apply, commit on save re-baseline, revertIfDirty on reload/close). Wired into all 6 panels (TMsSheetPanel, Gen5/Gen6TMsSheetPanel, Gen5/Gen6MoveTutorsSheetPanel). Headers still rebuild on apply; reload also rebuilds after a revert. Did NOT do the full working-copy refactor (would have to thread a working list through the shared dialog + each TMCompatibilityTableModel header build — large/risky).
- #35: moves panels build `frozenModel`/`mainModel` wrapper AbstractTableModels that the JTables listen to, delegating to the inner `tableModel`; inner `setValueAt` fires `fireTableCellUpdated` on the INNER model only (not the wrappers), and Gen5/Gen6 setters mutate sibling derived columns (min/max hits, trap, status type, crit). Bridged via `tableModel.addTableModelListener(e -> repaint both tables)` in MovesSheetPanel, Gen5MovesSheetPanel, Gen6MovesSheetPanel — same pattern already used by the import path and by EvolutionsSheetPanel.

Evidence: Gen5RomHandler.java L87; RomHandler.java L451-473; TrainerEditorPanel.java (ctor, buildTrainerInfo, openClassNamesDialog); EditorUtils.java (showMoveAssignmentDialog L842+ immediate setter.accept; new MoveListGuard L794-837); the 6 sheet panels' edit/save/reload/onWindowClosing; the 3 moves panels' createFrozenColumnTable.

Reason: Each claim matched the real code exactly; no refutations.

Next Step: Build with the IntelliJ Javac2 Ant task (GUI forms) to confirm compilation — not run here per EDIT-ONLY instruction.

## 2026-06-19 - Type-Effectiveness editor: ZERO (immunity) selectable + gated

Hypothesis: The Type Effectiveness editor omits/can't represent Effectiveness.ZERO (immunities), so users cannot add/remove immunities for gens that support it; needs ZERO added as a selectable value gated on a per-gen immunity capability.

Finding: Partially true.
- The enum already has ZERO (Effectiveness = ZERO, HALF, NEUTRAL, DOUBLE, QUARTER, QUADRUPLE; only the first 4 appear in tables, QUARTER/QUADRUPLE come from combine()).
- TypeTable.VALID_EFFECTIVENESSES already includes ZERO and setEffectiveness accepts it; the dialog's value array already listed ZERO. So at the data level ZERO was ALREADY selectable and ALREADY round-trips. The real gap was the absence of any *gating* — ZERO was offered unconditionally with the dialog blind to the gen.
- Round-trip verified for EVERY gen that returns hasTypeEffectivenessSupport()==true: Gen1/2/3 (AbstractGBRomHandler) + Gen4/5 (AbstractDSRomHandler) + Gen6 (explicit). Gen1-4 emit any non-NEUTRAL (incl. ZERO) as triples; Gen3/Gen4 even repoint the table/overlay to free space when adding entries grows it. Gen5/Gen6 use a fixed NxN byte matrix where ZERO=0 in any cell. Gen1/Gen2 are the only ones that can REJECT (writeTypeTable throws if nonNeutralEffectivenessCount exceeds 82 / 110); RandomizerGUI.openTypeEffectivenessEditor already catches that IllegalArgumentException and shows an error. Gen7 extends Abstract3DSRomHandler and does NOT override hasTypeEffectivenessSupport -> inherits false -> editor button disabled, no silent no-op.

Evidence: TypeEffectivenessEditorDialog.java; Effectiveness.java; TypeTable.java (VALID_EFFECTIVENESSES L12-14, setEffectiveness L106-111); Gen1RomHandler.readTypeTable/writeTypeTable L1397-1471; Gen2 L2333-2426; Gen3 L4196-4372 (repoint); Gen4 L5757-5829 (overlay repoint); Gen5 L3413-3499; Gen6 L3060-3141; AbstractGBRomHandler L481-484 / AbstractDSRomHandler L513-516 (support=true); AbstractRomHandler L487-491 default false + no-op setTypeTable; RandomizerGUI.openTypeEffectivenessEditor L3961-4008 (gate + try/catch + ManualEditRegistry log).

What Changed: Added explicit capability RomHandler.hasTypeImmunityEditSupport() defaulting in AbstractRomHandler to mirror hasTypeEffectivenessSupport() (so all current handlers report true = correct). Dialog now takes an allowZero flag (4-arg ctor; old 3-arg delegates with true) and builds its selectable set from EFFECTIVENESSES_WITH_ZERO / _WITHOUT_ZERO; both the table cell editor combo and the detail-grid editEffectiveness combo use it. RandomizerGUI passes romHandler.hasTypeImmunityEditSupport(). Build: BUILD SUCCESSFUL (290 files).

Reason: ZERO was already functional but ungated; the change makes immunity support an intentional, future-proof capability while changing zero current behavior (every supported gen still gets ZERO).

Next Step: Runtime spot-check on one ROM per family (e.g. Gen3 FireRed: add Water immune to Fire, save, reopen editor -> immunity persists; Gen1: confirm the >82 non-neutral guard shows the error dialog rather than corrupting). The allowZero==false branch is currently unreachable from the GUI (no supported gen returns false); if ever used with a table that already contains ZEROs, harden editEffectiveness so a pre-existing ZERO isn't silently overwritten by combo index 0.

## 2026-06-19 - Per-forme egg-move editing Gen5/Gen6 (audit #18 follow-up)

Hypothesis: Alt-forme egg-move edits are shown but silently discarded at save in BOTH Gen5 and Gen6 panels; the feature needs the forme key wired into getEggMoves/setEggMoves so forme edits round-trip.

Finding: Partially true / Mixed.
- Gen5: ALREADY FIXED by the prior pass. Gen5RomHandler.getEggMoves/setEggMoves loop i=1..pokemonCount only (base-species-only NARC; no forme files, unlike getMovesLearnt which uses a formeOffset). The panel correctly makes forme rows read-only (isFormeRow → model isCellEditable false + Add/Remove guards). Trace is airtight: the only write paths (cell edit, CSV import) both go through model.setValueAt which checks isCellEditable, so no forme key can ever reach setEggMoves. Gen4/Gen3/Gen2 panels subclass Gen5 panel → covered.
- Gen6: WAS STILL BUGGED. Gen6RomHandler is also base-species-only (loop 1..721, file format = count@0), but Gen6EggMovesSheetPanel had NO forme guard → forme rows editable, edits dropped at save with a "success" dialog (exactly audit #18).
- The feature genuinely CANNOT be implemented for Gen5/Gen6 ROMs: those EggMoves archives have no per-forme storage. Only Gen7 does (Gen7RomHandler reads a forme-reference word, builds altFormeEggMoveFiles, round-trips keyed by altForme.getNumber(); file format = forme-ref@0, count@2 — a DIFFERENT format from Gen6).

Evidence: Gen5RomHandler.java getEggMoves/setEggMoves (1877/1899) vs getMovesLearnt (1813, uses formeOffset); Gen6RomHandler.java (2409/2431) vs getMovesLearnt (2374); Gen7RomHandler.java (2056/2104, full forme support); RomHandler.java getEggMoves javadoc; Gen7EggMovesSheetPanel extends Gen6EggMovesSheetPanel; EditorDataCache keys by species.getNumber(); BUGHUNT_2026-06-12.md #18.

What Changed: The Gen7 panel reuses Gen6's class, so a blanket forme-block in Gen6 would have wrongly disabled Gen7's legitimate per-forme editing. Solution = capability-driven:
1. RomHandler.java: new `default boolean supportsFormeEggMoves() { return false; }`.
2. Gen7RomHandler.java: override → true (only handler that round-trips forme egg moves).
3. Gen6EggMovesSheetPanel.java: cache `formeEggMovesSupported`; new `isReadOnlyFormeRow(row)` (false when supported); gate model.isCellEditable + addEggMoveSlot + removeEggMoveSlot. Result: Gen6 forme rows read-only (no silent discard), Gen7 forme rows fully editable & persisted.

Map-key consistency (load vs save): CONSISTENT. Load puts eggMoves[species.getNumber()]; panel reads/writes eggMoves[species.getNumber()] (species from getSpeciesInclFormes, formes have distinct numbers); Gen7 setEggMoves reads eggMoves[altForme.getNumber()]. Same key throughout → forme round-trip holds. Base/forme keys are distinct map entries, so neither clobbers the other.

Build: clean Javac2 (rm -rf out) 290 files, BUILD SUCCESSFUL.
Next Step: Runtime check needs ROMs — (a) Gen6 XY/ORAS: confirm forme rows (e.g. Mega/Rotom) render read-only with the "edit the base species" dialog; base-species egg-move edits still save. (b) Gen7 SM/USUM: edit a forme that has its own egg moves (e.g. an Alolan forme), Save, reload, confirm the forme's egg moves changed and the base forme's did not.

## 2026-06-19 - Learnset sheet: remove 20-slot cap + implement Add/Remove Move (Features #12/#13)

Hypothesis: A hard `maxMovesPerPokemon = 20` truncated >20-move learnsets on display and risked
dropping the extra moves on save; the Add/Remove Move toolbar buttons were stubs.

Finding: Partially true. Display WAS capped at 20 (base, Gen5; Gen6 used max(1,largest) with no spare).
Save was NOT actually at risk of truncation — save() reads the full underlying movesLearnt list via
createNormalizedLearnsets (independent of the column cap) — but the >20 moves were invisible/uneditable.
Add/Remove were confirmed stubs (info dialogs only) in all three standalone panels.

Evidence: Read LearnsetsSheetPanel / Gen5 / Gen6 / Gen7 / Gen3 / Gen4 panels, MoveLearnt.java,
EditorUtils.exportTableToCSV + applyCsvDataToTable, TableLayoutDefaults (reordering off,
autoCreateColumnsFromModel left true). Gen3/Gen4 subclass Gen5; Gen7 subclasses Gen6.

What Changed: Cap is now dynamic in all 3 standalone panels: max(20 floor, largest-learnset + 4 spare),
computed in determineMaxSlots(). Table model maxMoves/columnNames made growable with ensureCapacity()
+ buildColumns(). CSV export/import need no change (both are driven by tableModel.getColumnCount()).
Add Move appends new MoveLearnt(0,1) to the selected species (harmless; save drops move<=0), grows
columns if needed, refreshes + scrolls to the new slot. Remove Move deletes the slot under the selected
column (or the last slot) for the selected species only. Critical gotcha: the main table is backed by a
WRAPPER AbstractTableModel, so structure events must fire on mainTable.getModel(), NOT the inner
tableModel (refreshTableStructure handles this, then re-applies setupMainTableColumns to re-attach combo
editors). BUILD SUCCESSFUL (290 files, JDK8 javac2).

Next Step: Runtime-test on a Gen7 ROM (movesets can exceed 20): confirm a >20-move species shows all
moves, Add/Remove operate only on the selected row, and a save round-trips all entries.

## 2026-06-19 - TM move + Move-tutor move assignment editors (Features #8/#9)

Hypothesis: the TM/tutor compatibility sheets edit only the WHO-learns-it matrix; setTMMoves/setMoveTutorMoves were never called, and the per-TM/tutor move-name column headers are baked into each *TableModel constructor from getTMMoves()/getMoveTutorMoves(), so reassigning a TM/tutor move requires (a) a setter call and (b) a model/header rebuild to show the new name live.
Finding: True.
Evidence: TMsSheetPanel / Gen5TMsSheetPanel / Gen6TMsSheetPanel + Gen5MoveTutorsSheetPanel / Gen6MoveTutorsSheetPanel — columnNames[] built once in each model ctor from the move lists; no setTMMoves/setMoveTutorMoves anywhere in the UI. RomHandler.java:377-412 (getTMMoves/setTMMoves/getTMCount, hasMoveTutors/getMoveTutorMoves/setMoveTutorMoves). Gen5RomHandler.setMoveTutorMoves:3781 returns immediately for Type_BW (BW1 no-op) and returns on `moves.size()!=amount` (BW2 size-mismatch discard) — confirms the audit note; mitigated by always passing a list of exactly getMoveTutorMoves().size(). Frame→panel map: Gen1/2→TMsSheetPanel; Gen3/4→Gen5TMsSheetPanel (subclass); Gen5→Gen5TMsSheetPanel; Gen6→Gen6TMsSheetPanel; Gen7→Gen6TMsSheetPanel (subclass). Tutors: Gen3/4/5→Gen5MoveTutorsSheetPanel; Gen6/7→Gen6MoveTutorsSheetPanel (subclass). Gen1/2 frames omit the tutor tab (hasMoveTutors()==false).
What changed: added EditorUtils.editTMMoves / editMoveTutorMoves (public) + private showMoveAssignmentDialog — a modal JOptionPane table (read-only "TM #"/"Tutor #" col + searchable "id: name" move combo via installSearchableComboBox), edits an int[] working copy, OK builds a List<Integer> of EXACTLY the getter size in order (clamps invalid ids, falls back to the original id per slot, never writes null), calls the setter, logs diffs ("TM26: Earthquake -> Stone Edge") to registry sections "TM Moves"/"Move Tutor Moves", returns true iff something changed. Each of the 5 base panels got a purple "Edit TM Moves..."/"Edit Tutor Moves..." toolbar button (gated on !getTMMoves().isEmpty() resp. hasMoveTutors()&&!tutorMoves.isEmpty()) and a rebuildTable() that swaps the CENTER container (compat edits live in the tmCompatibility/tutorCompatibility maps and survive the rebuild) so headers refresh live. Setter skipped entirely when nothing changed (avoids redundant ROM writes).
Reason: BUILD SUCCESSFUL, 290 source files compiled (Java 8, IntelliJ javac2 Ant task). Subclasses inherit the button via the shared toolbar builder.
Next Step: runtime-test — open each gen's TMs Sheet + Move Tutors tab, reassign a TM/tutor move, confirm the column header updates immediately and the registry log shows the change; verify BW1 tutor edits are silently dropped (engine no-op) without a crash and BW2 edits persist.

## 2026-06-19 - Item editor added (Allowed/Bad flag toggles)

Hypothesis: item names/IDs are NOT editable (final fields, no RomHandler setter), but the Allowed/Bad flags ARE meaningful and mutable, and toggling them genuinely changes the randomizer's item pools.
Finding: True.
Evidence: Item.java:12-13 (final id+name), :43/:51 (setAllowed/setBad mutators) and :59 (setTM). AbstractRomHandler.java:666-673 — getAllowedItems() = getItems().filter(isAllowed); getNonBadItems() = getAllowedItems().filter(!isBad). The ONLY setItems in the codebase is Shop.setItems(List<Item>) (shop contents) — there is NO item-list/name setter on RomHandler (getItems() at RomHandler.java:473 returns a read-only list). Gen3RomHandler.getItems() returns the live backing `items` list by reference, so mutating those Item objects on save IS the persistence step (in-memory metadata, reset on ROM reload; never written to ROM bytes).
What changed: built ItemsEditorPanel.java (JTable: ID/Name/TM? read-only; Allowed/Bad as editable Boolean checkbox columns — TableLayoutDefaults.installStripedRenderers registers no Boolean renderer, so JTable's native checkbox editor is used). Apply-on-save model: snapshot orig (allowed,bad,tm) into arrays at construction, edit working boolean[] copies, push onto live Item objects only in save() via setAllowed/setBad (TM left read-only to preserve the field-item TM invariant), log to "Item Flags" registry; no setItems call. Top muted note + Find button (EditorUtils.showFindDialog/performFind). Tab wired into all 7 gen frames after Pickup Items, gated on !getItems().isEmpty().
Reason: BUILD SUCCESSFUL, 290 source files compiled.
Next Step: runtime-test that toggling Allowed off for an item removes it from a re-randomized shop/field/pickup pool, and that flags reset on ROM reload (expected — in-memory only).

## 2026-06-19 - Field-Item editor added (overworld / hidden items)

Hypothesis: a Field-Items editor can reuse the EncountersEditorPanel template and the setFieldItems TM-ness invariant can be guaranteed by per-row same-TM-ness combos.
Finding: True.
Evidence: RomHandler.java:495-505 (setFieldItems throws IllegalArgumentException unless TM-ness matches getFieldItems() per index); ItemRandomizer.randomizeFieldItems (lines 57-86) confirms the invariant and the pools — TM pool = getItems() filtered by isTM(); non-TM pool = getAllowedItems() minus TMs. Built FieldItemsEditorPanel.java (#, TM?, Item combo); slotIsTM[] captured from the ORIGINAL list is the contract anchor, and a per-row ItemCellEditor swaps between a TM combo and a non-TM combo so a row can only resolve to same-TM-ness items. save() rebuilds a same-size list, guards each index (reverts to original on any TM-ness flip or null), logs to "Field Items" registry. Tab wired into all 7 gen frames after In-Game Trades, gated on !getFieldItems().isEmpty() in try/catch.
Reason: build compiled all 288 files (BUILD SUCCESSFUL), 4 FieldItemsEditorPanel*.class emitted; invariant preserved by construction + defensive guard.
Next Step: runtime-test in a real ROM (e.g. Gen3 FRLG) that the TM slots show only TMs, edits round-trip, and the ROM still boots after save.

## 2026-06-13 - Deep audit #2: ROM-patch integrity, trainer roles, scaling, usability

Workflow (6 reviewers -> adversarial verify -> synthesis; resumed after a transient server rate-limit, script made null-safe). 19 confirmed, 2 refuted. Fixed 18; 2 deferred.
HIGH — ROM corruption / crash / data-loss (FIXED):
- Gen3RomHandler.setEggMoves wrote the whole egg-move block into the shared rom[] with NO bounds check -> Card View "add egg move" beyond the vanilla block size corrupted adjacent ROM data (unbootable). FIX: scan the original 0xFFFF terminator, never write past it, drop overflow (mirrors Gen4/Gen5).
- Gen7 trainer IV is a packed 32-bit field (bit 30 = SHINY, set by TrainerPokemonRandomizer:296); the editor clamped it to 0-31 and wiped the shiny/high bits. FIX: Gen7 IV row shows getIVs()&0x1F and commits (getIVs()&~0x1F)|(v&0x1F); Gen6 still strength-synced; Gen3/4/5 plain.
- Gen5 BW2 PWT (Driftveil) writer called tpks.next() blindly for a fixed 3/6 slot block -> removing a Pokémon crashed the ROM save. FIX: guard !tpks.hasNext() (leave vanilla slot data).
- Gen3 Emerald Mossdeep-Steven fixed 3-slot writer did pokemon.get(i) for i<3 -> shrinking the team threw IndexOutOfBounds. FIX: loop `i<3 && i<size`.
- Evolution-line stat scaling: originalStats snapshotted base species only (getSpecies()), but evolutionFamily includes alt-formes -> formes fell back to live (scaled) values and drifted/corrupted. FIX: snapshot over getSpeciesInclFormes().
MEDIUM — correctness (FIXED):
- Move Card "Secondary Effect %" wrote only secondaryEffectChance, but Gen5/6/7 persist statusPercentChance (same byte) -> edit silently lost + duplicate field. FIX: hide the Core field for gen>=5 (Effect Details "Status Chance %" is canonical) and mirror secondaryEffectChance from it.
- FRLG rival-as-champion: maxEliteAce was the max over ALL elite teams incl. round-2 rematch, inflating the threshold so the real round-1 champion was dropped. FIX: threshold = each elite milestone's lowest-index (round-1) representative ace.
LOW — data-loss (FIXED):
- commitFocus() used async transferFocus(), so a value typed then saved via File>Save All (no focus change) was dropped. FIX (all 3 panels): ((JTextField)focus).postActionEvent() commits synchronously.
USABILITY (FIXED, via subagent): Move name field made display-only (names aren't persisted; also fixes blank-name-removes-from-list); Gen3 category combo disabled+explained; "Status/Flinch Chance %" -> "(0-255)"; the 5 "All changes saved" dialogs reworded to say edits write to the ROM on main-window Save/Randomize; gender-ratio + EV-yield(0-3) range hints; trainer auto->custom-moves tooltip; level-curve slider "overwrites typed targets" tooltip.
DEFERRED (noted): (9) SM/USUM kahunas who are ALSO Elite Four members bundle both teams under "Grand Trial N" (intricate Gen7 split; labels otherwise correct). (13) index combos display index 0 when the stored value is out of range (display-only, no data loss).
INTEGRITY CAVEATS from the completeness critic (need a ROM + save to confirm; ride the randomizer's existing save path): GBC (Gen1/2) global/header checksum is never recomputed (no editors there though); 3DS NCCH hash/integrity recompute on Gen6/7 save unverified; no read-back round-trip verification; editor->writer convergence not traced end-to-end per gen. These are pre-existing/out-of-editor-scope; flagged for a future ROM-in-hand pass.
Verified: clean Javac2 build (281 files, no dup methods), clean jar launch (alive 8s). Runtime/save verification needs a ROM.
Next Step: user loads a ROM, makes edits across editors, Saves the ROM, and re-opens it to confirm edits stuck + the game boots (esp. Gen3 egg moves, Gen7 trainer IVs/shiny, BW2 PWT / Emerald Steven team-size, FRLG level curve).

## 2026-06-13 - Editor polish: bigger fonts, fix line-scale collapse, Reset-to-default

Three user-reported items, all fixed:
1. FONTS too small in the editor windows. Fix: ThemeManager dark branch now sets `UIManager.put("defaultFont", Segoe UI 14)` BEFORE FlatDarkLaf.setup() — verified FlatLaf derives all component fonts from it (Label.font=14 after applyTheme). Also bumped the hardcoded FONT_* constants in PokemonCardViewPanel + MoveCardViewPanel by +2 (label 12->14, value 12->14, small 11->13, section 14->16, title 26->28, chip 11->13) so the custom-painted card text matches.
2. LINE-SCALE COLLAPSE BUG: dragging a stat to the 255 cap then back down made the whole evolution line "sync" to one value. Root cause: the baseline was captured lazily from CURRENT (post-clamp) values and re-captured on navigate/toggle. Fix: scale from the ORIGINAL ROM-load base stats instead. PokemonCardViewPanel now snapshots each species' 6 base stats in fetchData() into `Map<Species,int[]> originalStats`; propagateLineScale(sp, statIndex, newVal) computes target = clamp(origM + round((newVal-origEdited) * (origM/origEdited)^k)). Fully path-independent — unit-verified: Bulba HP 45->255 (Ivy/Venu clamp 255) -> back to 45 restores 60/80 (not synced); ->100 gives 133/178. Removed the old lazy lineScaleBaseline map + its clears.
3. RESET TO DEFAULT button added to the Base Stats section: restores THIS species' 6 base stats to originalStats (ROM-load values), does NOT touch evolutions; logs "reset base stats to default" and refreshes the card.
Verified: clean Javac2 build (281 files, no dup), Label.font=14 dump, scaling unit test, clean jar launch (alive 8s).
Next Step: user confirms readability + tries drag-up-then-down (no more sync) and the Reset button on a real ROM.

## 2026-06-13 - New feature: Move Card View (per-move card editor)

Request: a card-style editor for moves mirroring the Pokémon Card View (instead of only the moves spreadsheet).
Built: new `editors/MoveCardViewPanel.java` (per-move card: filterable sidebar move list + card sections for core stats, flags, and Gen5+ effect/stat-change/contest blocks), added as a "Move Card" tab in all 5 EditorFrames (field + addTab after the Card View tab + save() after movesSheetPanel.save()).
Key correctness facts: Move fields are PUBLIC + MUTABLE (direct assignment, no setters); moves persist by LIVE-OBJECT mutation — there is NO setMoves(), so save() only commitFocus()+logs to ManualEditRegistry under "Move Card" (do NOT mimic PokemonCardViewPanel.save()'s map setters). `romHandler.getMoves()` is the live list (auto two-way sync with the moves sheet). Per-gen field gating mirrors the move sheets (Gen3/4 base + Gen4 extras + Gen5+ full set). Skeleton (sidebar/sections/number-commit/theme/save) copied from PokemonCardViewPanel.
Process: authored by a subagent from a recon contract, then adversarially reviewed. Review found + I FIXED: (1) HIGH — Status Type wrote only `statusType` but the ROM persists `statusEffect`; now sets both (TOXIC_POISON→POISON.ordinal() else ordinal, no-op-guarded) like the sheet; (2) MED — Crit Stage now also sets the coupled `criticalChance` enum (>=6 GUARANTEED / >0 INCREASED / else NORMAL); (3) LOW — Effect Index max gated to 255 for Gen3 (byte) vs 65535 for Gen4+. Intentionally omitted (sheet-only): categoryQuality + derived statChangeMoveType/statusMoveType (avoid silent desync); hitCount (derived/not-saved); internalId/number (read-only).
Verified: clean Javac2 build (281 files, no dup methods), MoveCardViewPanel.class present, clean jar launch (alive 8s). Note: the panel needs a loaded ROM to render, so live UI not auto-tested.
Next Step: user opens an editor on a Gen3/4 ROM and a Gen5+ ROM, picks a move, edits power/type/flags/status/crit, saves, and confirms it sticks (and shows in the .log "Move Card" section).

## 2026-06-13 - New feature: scale base-stat edits across the evolution line

Request: in the Pokemon editor, propagate a base-stat edit to the whole evolution line, with a tunable Absolute<->Proportional strength (user explicitly wanted the proportional side dampened, not a flat %, scaled relative to the stat).
Design chosen (via AskUserQuestion): a single "Scale edits across evolution line" toggle + a strength slider. The slider is the dampening exponent k of a unified formula:
  delta_m = round( delta_edited * (base_m / base_edited) ^ k ),  clamped 1..255
  k=0 -> flat delta (Absolute); k=0.5 -> square-root (balanced, default); k=1 -> same % (Full). Anchor = the specific stat being edited.
Implementation:
- EditorUtils.evolutionFamily(Species): connected component over getEvolutionsFrom()/getEvolutionsTo() (handles branches: Eevee, Wurmple, Tyrogue).
- PokemonCardViewPanel: fields lineScaleEnabled / lineScaleK / lineScaleFamily / lineScaleBaseline; UI (checkbox + JSlider 0..100 -> k, labels Absolute / Full % / mode) added under the Base Stats grid; per-stat apply() captures oldVal and calls propagateLineScale(sp, statIndex, old, new). Baseline (per stat, per shown species) is captured on first touch so dragging is path-independent (not compounded) and cleared on species change / toggle / slider move. statByIndex/setStatByIndex map the 6 stats. Logs "scaled base stat across N evolution(s)".
Verified: unit test of the formula — Bulbasaur HP 45->55 (+10): Absolute Ivy/Venu +10/+10; k=0.5 +12/+13; Full% +13/+18; downward (edit Venusaur) gives lower evos a smaller share; clamps at 255. Clean Javac2 build (no dup) + clean jar launch.
Next Step: user tests on a real ROM (e.g. buff Bulbasaur HP with the slider at √ and confirm Ivysaur/Venusaur scale).

## 2026-06-13 - Dark mode: deepen the background grey (follow-up)

Request: FlatLaf's dark grey was too bright. ThemeManager.darkenDarkBackgrounds() (after setup) subtracts 18 per channel from ~32 background-family keys: panel/toolbar/tabs #3C3F41 -> #2A2D2F, tables/fields #46494B -> #343739, buttons proportionally. Borders (#616365), grid (#5A5E60), selection (#4B6EAF) and text (#F0F0F0 / disabled #C0C0C0) are left untouched. EditorTheme + CardView dark backgrounds darkened to match (surface #343739, panel #2A2D2F). Verified via UIManager dump (no AWT render needed). Clean build (no dup) + clean jar launch.

## 2026-06-13 - Level-curve "scale all trainers" toggle + brighter dark text

Request: (1) a toggle so route/optional trainers (not just gym/E4/champion) scale with the curve; (2) brighter, higher-contrast dark-mode text.
Done:
- gui/TrainerLevelCurveDialog.java: new checkbox "Also scale every other trainer (routes, rivals, optional fights)". apply() now tracks a `handled` set (all milestone boss + gym-if-checked teams) and, when the toggle is on, remaps EVERY other trainer's levels via remapLevel() — a piecewise-linear curve through the milestone (currentAce -> target) points (buildCurvePoints()). Below the first / above the last milestone it shifts by that endpoint's delta; in between it interpolates. No double-shift (handled set). Unit-verified: pts {12->15, 50->60} maps Lv30->36, Lv5->8, Lv70->80; identity when nothing changed.
- Contrast: ThemeManager.applyDarkTextContrast() (after FlatDarkLaf.setup()) overrides ~35 foreground keys to #F0F0F0 and ~16 disabled keys to #C0C0C0 (FlatLaf defaults were #DDDDDD / #A6A6A6). EditorTheme text()->#F0F0F0, mutedText()->#C0C0C0, strongText()->white; CardView TEXT_PRIMARY->#F0F0F0, TEXT_MUTED->#C0C0C0. Also the level-curve dialog's hardcoded Color.GRAY labels now use a theme-aware mutedColor() (#C0C0C0 in dark). Light mode untouched.
Reason: clean Javac2 build (no dup methods) + clean jar launch (alive 8s) + render shows the brighter text + remapLevel unit test passes.
Next Step: user tests "scale all trainers" on a real ROM (e.g. widen HGSS curve and confirm route trainers scale too).

## 2026-06-13 - Deep multi-agent audit: 17 confirmed issues, all fixed

Hypothesis: The session's new editor features (Card View, Trainer Editor, Level Curve, theme, logging) contain bugs and wrong/misleading trainer-order labels (the "uber below champion / Ghetsis" class).
Finding: True. A workflow (8 reviewers -> adversarial verify -> synthesis) confirmed 17 issues (2 reviewer claims refuted). 0 critical, 3 high, 6 medium, 3 low, 3 nits. All fixed this session.
Evidence + fixes (file : what):
  ORDER/WORDING (user priority), all in gui/TrainerLevelCurveDialog.java:
  - FRLG E4+Champion were silently dropped: ELITE regex was ELITE(\d+) (full match) but FRLG tags are ELITE1-1..ELITE4-2 (round-suffixed) and it has no CHAMPION tag (champion = rival). FIX: ELITE = ELITE(\d+)(?:-\d+)? (rounds fold by number); added rival-as-champion (promote RIVAL/FRIEND fights with ace >= max-elite-ace) guarded to FRLG (getROMType()==Gen3Constants.RomType_FRLG) and Gen7. Verified by regex/label unit test.
  - Gen7 SM/USUM: kahunas were mislabeled "Elite Four 1-4". FIX: eliteLabel(gen,n) -> Gen7 ELITE1-4 = "Grand Trial N", ELITE5+ = "Elite Four (N-4)"; champion derived from final rival/friend.
  - B2W2: postgame "restaurant brothers" (GYM9/10/11-LEADER) were folded into Gym 1. FIX: gate the gen5 trio-fold to BW1 only (isBW2 = getROMType()==Gen5Constants.Type_BW2 -> exclude them).
  - #9 delta keyed on one team but applied to all sharing the tag: now discloses team count ("N teams (incl. rematches)") in the edit log.
  HIGH data-loss, pokemon/editors/TrainerEditorPanel.java:
  - Gen6 trainer IV edits were dropped (writer uses the 'strength' byte, not getIVs). FIX: syncGen6Strength() updates strength on IV edit (ORAS: round(iv*255/31); XY: low-5-bits).
  - Custom moves on a newly-added trainer mon reverted to level-up (resetMoves never cleared). FIX: enableCustomMoves() now setResetMoves(false) on every mon.
  - "have items"/"have custom moves" couldn't be turned OFF (save() force-re-enabled from leftover data). FIX: unchecking now clears the data (heldItem=null / moves={0,0,0,0}+resetMoves).
  MEDIUM/LOW, pokemon/editors/PokemonCardViewPanel.java:
  - EV-yield field accepted 0..255 but it's a 2-bit field -> FIX clamp 0..3. Base Exp. Yield accepted 0..65535 but Gen3/4 store 1 byte -> FIX max = gen<=4?255:65535.
  - Tutor "Add" picked the wrong slot on duplicate move names (pickFromList resolved by label text) -> FIX track each visible row's original index (robust to duplicates, all callers).
  - Card scroll reset to top on tab re-show/theme refresh -> FIX refresh() preserves scroll.
  THEME (cooked UI), ThemeManager + EditorTheme + 5 frames + CardView:
  - EditorTheme/frames read the LIVE pref at paint while the LaF is restart-only -> toggling with an editor open half-repaints. FIX: ThemeManager.isDarkModeApplied() snapshots the installed theme in applyTheme(); all 7 rendering-time reads use it (menu checkbox still uses the live pref).
  LOGGING, gui/RandomizerGUI.java:
  - loadQS() (settings reload, same ROM) wiped the manual-edit registry via romLoaded(), so the .log omitted edits. FIX: moved registry.clear() out of romLoaded() into the genuine new-ROM sites (openRom !reinitialize, presetLoader); loadQS no longer clears.
  NITS: "+ Add Pokemon" -> "+ Add Pokémon"; sprite-flip double-label; stale initFontRendering comment.
Reason: Clean Javac2 build (no dup methods), real-jar launch clean, regex/label logic unit-verified. Runtime testing of the dialogs needs a ROM (none available) — see the audit's 12-step manual checklist.
Next Step: user runs the manual checklist (esp. FRLG/Gen7/B2W2 level curve, Gen6 trainer IVs, added-mon custom moves) on real ROMs.

## 2026-06-13 - Dark mode switched to FlatLaf (supersedes the Metal theme)

Hypothesis (user question): can we have the *native Windows widgets* but dark, or is that a Java limit?
Finding: It is a hard limit. Light mode uses WindowsLookAndFeel, whose controls (checkboxes, radios, tabs, buttons, scrollbars) are painted by the OS via UxTheme in the *light* visual style; Windows exposes no dark variant of those classic Win32 theme parts to a Java app, so they cannot be recoloured from Java. A fully dark UI therefore REQUIRES a self-painting (pure-Java) look and feel. Confirmed.
Decision: user chose FlatLaf (FlatDarkLaf) over the hand-tuned Metal theme.
What changed:
  - Added dependency `flatlaf-3.7.1.jar` at repo root (downloaded from Maven Central; Java 8 bytecode / major 52, verified). 1,010,321 bytes.
  - ThemeManager rewritten: dark branch = `FlatDarkLaf.setup()`; light branch unchanged (WindowsLookAndFeel). Removed the DarkMetalTheme class + applyDarkUiDefaults() + initFontRendering body (FlatLaf manages fonts/AA/HiDPI itself). setDarkMode still persists+flushes; theme still applies on restart only.
  - BUILD RECIPE UPDATE: `flatlaf-3.7.1.jar` MUST be on the Javac2 compile classpath (added to upr_build.xml `<javac2><classpath>`), AND its classes bundled into the fat jar. Bundle step: after overlaying app classes, `jar xf flatlaf-3.7.1.jar` into a temp dir, delete its META-INF/MANIFEST.MF (keep the app's manifest!), then `jar uf launcher/UPR-FVX.jar -C <flatlaf_x> .`. Jar grew 78.4MB -> 79.4MB. Verified 393 com/formdev/flatlaf entries + FlatDarkLaf.properties present, Main-Class intact.
Reason: rendered the themed widgets to PNG (printAll) -> clean flat dark, legible disabled text, blue selected-tab underline, visible spinner, distinct fields; real-jar launch clean (FlatLaf loaded, no ClassNotFound, only the benign prefs/SSL warnings).
Next Step: editor panels (Card View/Trainer/sheets) still use their own EditorTheme dark palette; they'll be dark but may not exactly match FlatLaf's shade — align EditorTheme to FlatLaf colors if the user wants pixel-consistency. The prior Metal-contrast entry below is now historical (that code was removed).

## 2026-06-13 - Dark mode "doesn't look right" -> Metal auto-derived colors are illegible (fixed)

Hypothesis: Dark mode looks washed-out/wrong because Metal's white->black color derivation produces too-low contrast for disabled text, some foregrounds, and borders.
Finding: True (confirmed by a deterministic UIDefaults contrast audit + a rendered screenshot).
Evidence: Dumped all 271 Metal color keys with DarkMetalTheme active (Tools: temp UIDump). Computed WCAG contrast vs panel bg #3C3F41: ALL disabled/inactive text = #323436 = 1.18:1 (invisible); Spinner.foreground = #3C3F41 = 1.00:1 (= bg, invisible numbers); controlHighlight/controlLtHighlight = #3C3F41 = 1.00:1 and controlShadow = #323436 = 1.18:1 (group-box etched borders + button bevels melt into bg); text-field backgrounds = panel bg (fields don't stand out). Verified the user's screenshot symptom: with no ROM loaded almost every control is disabled -> the whole window looked washed out because disabled text was ~invisible.
Fix: new ThemeManager.applyDarkUiDefaults() (called after setLookAndFeel in the dark branch) overrides the bad keys with audited values: disabled text #979B9F (~3.7:1, muted but legible) across Button/CheckBox/RadioButton/ToggleButton.disabledText + *.disabledForeground + *.inactiveForeground + textInactiveText; Label.disabledShadow -> bg (kills muddy 1px shadow); Spinner.foreground -> #D6D8DA; input bg #45494A (TextField/Formatted/Password/TextArea/EditorPane/TextPane/ComboBox/Spinner/List/Table) so fields read as editable; bevels controlHighlight #54585B / controlLtHighlight #5E6266 / controlShadow #2B2D2F / controlDkShadow #1C1D1E; Separator + TabbedPane edge colors; menu acceleratorForeground #9A9EA2; and TitledBorder.border -> LineBorderUIResource(#54585B) so group boxes have a crisp full outline like light mode.
Reason: Verified two ways: (1) UIVerify prints UIManager.getColor(key) post-apply = all new values; (2) rendered the themed widgets (disabled + enabled + spinner + fields + group boxes) to PNG via printAll (Metal is pure-Java painting) -> disabled text legible, spinner shows 50, fields distinct, group boxes have clear borders. Clean Javac2 build (no dup methods) + real-jar launch clean. NOTE: java.exe GUI can't be granted to computer-use (no Start-menu reg), so offscreen printAll-to-PNG is the verification method for this app's UI.
What Changed: Dark mode is now legible and structured ("regular but dark"), not washed out. Light mode untouched (overrides only run in the dark branch). Applies on restart (theme is startup-only by design).
Next Step: user confirms on their machine after relaunch; if they want even more pop, bump controlHighlight / disabled-grey slightly.

## 2026-06-13 - CRITICAL BUILD LESSON: incremental Javac2 double-weaves forms -> ClassFormatError

Hypothesis: Incremental rebuilds (reusing the upr_javac2_out dir) are safe for form-bound classes.
Finding: False. Re-running the Javac2 ant task over an output dir that already contains a woven form class re-instruments it, duplicating the generated `$$$getFont$$$` (and risking `$$$setupUI$$$`) method. The JVM then throws `java.lang.ClassFormatError: Duplicate method name&signature in class file com/dabomstew/pkrandom/gui/RandomizerGUI` at load (before main()), so the app won't start.
Evidence: Build 1 (29 files incl. RandomizerGUI) -> smoke test launched fine. Build 2 ("Compiling 1 source file" = ThemeManager only; RandomizerGUI NOT recompiled) -> ClassFormatError. `javap -p ... RandomizerGUI | sort | uniq -d` showed exactly one dup: `private java.awt.Font $$$getFont$$$(...)`. Clean rebuild (rm -rf out; full compile of 280 sources) -> `uniq -d` empty, app launches.
Fix/Rule: ALWAYS clean-build the form project: `rm -rf <out>` before the Javac2 ant run, every time. Never trust the incremental out dir for the fat-jar overlay.
Reason: Javac2's form-instrumentation pass runs over destdir .class files independently of javac's recompile decision, so unchanged form classes get re-woven.
Next Step: bake the `rm -rf out` into any future build invocation.

## 2026-06-13 - Three user-reported polish fixes (log sync, save popups, theme) - done

Hypothesis: (A) Manual editor edits can be appended to the randomization .log at a single end-of-log point; (B) the ~20 save popups come from each panel's own save() dialog cascading through saveAll(); (C) the "cooked" UI is runtime look-and-feel swapping plus disabled font antialiasing.
Finding: True (all three), verified by an investigation subagent + build/launch.
Evidence: (A) RandomizationLogger.logResults() writes to an in-scope PrintStream(baos); ManualEditRegistry is a process-wide singleton cleared only in romLoaded() (which the post-randomize reinit bypasses via the openRom !reinitialize gate), so edits survive to log time. (B) 19 distinct per-panel "...updated successfully!" showMessageDialog calls + 5 identical saveAll() methods, all enumerated by file:line. (C) ThemeManager.setDarkMode live-mutated via updateComponentTreeUI (unreliable for Windows LaF + editor panels that bake EditorTheme colors at construction); dark used swing.useSystemFontSettings=false which also kills ClearType AA -> skinny fonts.
Fix: (A) added RandomizationLogger.logManualEdits() (appends a "Manual Edits" section via ManualEditLogFormatter, omitted when empty) + TrainerLevelCurveDialog.apply() now logs to registry section "Trainer Level Curve". (B) added EditorUtils.suppressSaveDialogs flag; each saveAll() sets it in try/finally around the panel-save block; all 19 panel dialogs guarded with `if(!EditorUtils.suppressSaveDialogs)`; the single final "All changes saved successfully!" per frame is kept. (C) setDarkMode now only persists the pref (+PREFS.flush() for immediate, kill-safe persistence) and the menu shows a "applies on restart" notice; new ThemeManager.initFontRendering() (called first thing in main(), before AWT toolkit init) forces awt.useSystemAAFontSettings=lcd + swing.aatext=true in dark mode so fonts are crisp; light mode untouched.
Reason: Clean Javac2 build + GUI launch verified (only benign registry/SSL warnings). Confirmed HKCU user-prefs persist across JVM restarts (JVM2 read back what JVM1 wrote) despite the HKLM-root warning, so restart-based theming is sound.
Next Step: user testing — toggle dark mode, restart, confirm crisp dark fonts + clean light on toggle-back; do edits across Card View/Trainer/Level Curve, randomize, confirm the "Manual Edits" section in the .log and a single save confirmation.

## 2026-06-12 - Level Curve UBER ordering wrong for BW1 (caught by user) - fixed

Hypothesis: Generic CHAMPION-then-UBER milestone order is correct for all games.
Finding: False (wrong for BW1).
Evidence: Gen5Constants BW1 tags (1605-1612): CHAMPION=Alder (0x197); UBER=Game Freak Guy, Cynthia, Ghetsis (0xE8), N-White (0x24A), N-Black (0x24B). BW1's real story finale is N -> Ghetsis (UBER), fought after the E4; Alder (CHAMPION) is a postgame quirk, not the climax. B2W2 (1677,1713-1725): CHAMPION=Iris (real champion), UBER=Alder/Cynthia/Benga/N/Ghetsis/GF guys (all postgame). So UBER is a grab-bag (story finale + postgame fluff), clean only in HGSS/GSC (=Red).
Fix: dropped the generic UBER milestone from the standard chain; UBER is only appended (as Red) in the HGSS/GSC dual-region branch. So BW1 = gyms->E4->Alder(champion); B2W2 = gyms->E4->Iris; HGSS = gyms1-8->E4->champ->gyms9-16->Red. N/Ghetsis can be hand-levelled in the Trainer Editor.
Reason: javac + Javac2 build clean; GUI launch verified.
Next Step: user testing.

## 2026-06-12 - Level Curve tool (gyms/E4/champion) added to Foe-Pokemon tab

Hypothesis: A milestone level-curve reshaper can be added to the trainer settings tab using the tag-encoded fight order.
Finding: True
What: New `gui/TrainerLevelCurveDialog` + an "Adjust Level Curve (Gyms / E4 / Champion)…" button injected into the trainer (Foe Pokemon) settings panel.
- Button placement: mirrors `initTypeEffectivenessEditorUI` — `initLevelCurveButtonUI()` finds the trainer panel via the level-modifier checkbox's ancestor (confirmed GridBagLayout in the .form, line 1831) and adds the button at gbc gridy=99; enabled in enableOrDisableSubControls when romHandler != null.
- Milestones built from tags: GYMn-LEADER (BW GYM9-11 folded to gym 1), ELITEn, CHAMPION, UBER. Order follows tags (already per-version correct, incl. DP/Pt Fantina/Maylene swap). Dual-region (GSC/HGSS, gen2/4 with gym# > 8) stitched as gyms1-8 -> E4 -> champion -> gyms9-16 -> Red.
- Keyed on ACE level (max mon level of the representative/first boss trainer). Modes: Scale slider (proportional, anchor=first milestone, factor 0.25-3.0x), Normalize (minimal-fix monotonic), Even ramp, plus per-milestone target spinners (manual per-gap control). "Also shift each gym's regular trainers" checkbox (default on).
- Apply: delta = target - currentAce, added to every mon of the milestone's boss trainers (+gym regulars if checked), clamped 1-100; then romHandler.setTrainers(...). Persists on ROM save (like the editors).
Reason: javac + Javac2 build clean; GUI launches with button wired (verified after a flaky first timing read).
Next Step: user testing — confirm the button appears in the Foe Pokemon tab and the dialog reshapes correctly on Black (single-region) and ideally HGSS (dual-region chain).

## 2026-06-12 - REAL root cause of card-view add/remove "does nothing": rebuild() recursion

Hypothesis: After the chip-click + picker fixes, card-view add/remove STILL doesn't update the display.
Finding: True — and the actual root cause was a self-inflicted bug in PokemonCardViewPanel.rebuild():
    private void rebuild(Species sp) { SwingUtilities.invokeLater(() -> { if (currentSpecies == sp) rebuild(sp); }); }
It called rebuild(sp) recursively instead of showSpecies(sp), so it just re-queued itself on the EDT forever and NEVER redrew the card. Edits (ls.add, compat[i]=true, setType, etc.) were applied to the data correctly, but the view never refreshed — so add/remove/type/tag all "did nothing" visually (and it spun the EDT). 
Cause: an earlier blanket search-replace `showSpecies(sp);` -> `rebuild(sp);` (intended only for callback sites) also rewrote the one legitimate showSpecies call inside rebuild()'s own body.
Fix: rebuild() body restored to showSpecies(sp). (TrainerEditorPanel.rebuild was written fresh and already correct -> showTrainer.)
This — together with the prior mousePressed chip fix and the filter+JList picker fix — makes all card-view editing (TM/HM/tutor/egg add+remove, level-up add/remove, type change, etc.) actually reflect on screen.
Reason: javac + Javac2 build clean; launch verified.
Next Step: user testing; trainer renaming if wanted.

## 2026-06-12 - Card-view tag clicks dead + trainer moves blank — both fixed

Hypothesis A: Card-view add/remove tags don't fire because the Chip uses mouseClicked.
Finding A: True. The clickable Chip used MouseAdapter.mouseClicked, which only fires when press AND release land on the exact same pixel — any micro-movement cancels it, so "+ Add" and "×" silently did nothing (and the earlier picker fix was never reached). Changed to mousePressed (PokemonCardViewPanel.Chip) — fires on every press. Fixes TM/HM/tutor/egg add+remove and level-up add/remove. (Species switching worked because that's a JList, not a Chip.)

Hypothesis B: Trainer editor "doesn't pick up moves" for most trainers.
Finding B: True but by data design — only boss/leader trainers carry the custom-moves poketype flag; everyone else uses auto level-up moves, so tp.getMoves() was {0,0,0,0} → shown as "(none)". Fix: TrainerEditorPanel now displays the auto level-up moveset (RomFunctions.getMovesAtLevel at the mon's level) when the trainer isn't custom, labeled "Moves (auto)". Editing a move (or ticking "custom moves") calls enableCustomMoves(t): seeds every still-blank teammate with its level-up moveset, then sets the custom-moves flag — so converting to custom doesn't blank the other Pokémon.
Reason: javac + Javac2 build clean; launch verified.
Next Step: user testing.

## 2026-06-12 - Trainer Editor built (new "Trainers" tab, all gens)

Hypothesis: A full trainer editor can be built on getTrainers()/setTrainers() with per-gen field gating.
Finding: True
What: New `TrainerEditorPanel` added as a "Trainers" tab in all five Gen3-7 editor frames (wired into Save All + revert-on-close + setSaveAction). Layout: searchable trainer list (left) + scrolling list of editable team cards (right), per the user's chosen design.
Editable (gen-gated to what actually persists, per the persistence investigation):
- All gens: species, level, held item, IVs (0-31), 4 moves (auto-sets the custom-moves poketype flag), trainer poketype flags (items / custom moves).
- Gen5+: ability slot (labels from the species' abilities), gender (Random/Male/Female), forme number.
- Gen7 only: nature (25), 6 EVs (0-255).
- Add/remove team members (cap 6, min 1). Export all trainers to .TXT.
Read-only (not persisted / unreliable): trainer class (shown as chip), tag, multi-battle status, mega/Z (set via held item). Trainer NAME read-only in v1 (Gen3 = in-struct, Gen5/7 = separate setTrainerNames table; renaming is a fast-follow).
Persistence: edit working copy of getTrainers() -> setTrainers() in save() (required for Gen5/7 write-through; Gen3 caches live so onWindowClosing reverts the live list in place). Theme-aware via EditorTheme.
Reason: javac + Javac2 build clean; launch verified. Contract in this session's investigation (getTrainers fresh for Gen5/7 vs live cache for Gen3; field serialization matrix per gen).
Next Step: user testing. Fast-follows: trainer renaming (Gen3 via Trainer.name, Gen5/7 via setTrainerNames), inline card evolution editing.

## 2026-06-12 - Card "+ Add" (TM/HM, tutor, egg, level-up) silently added nothing — fixed

Hypothesis: The tag/"+ Add" pickers add nothing while in-place dropdowns/number fields work, so the picker is at fault.
Finding: True
Evidence: pickFromList/pickMove showed an EDITABLE searchable combo (EditorUtils.installSearchableComboBox sets setEditable(true) + filters the model) inside a modal JOptionPane. After selecting, getSelectedItem() returns the editor's (possibly unmatched/typed) text, so labels.indexOf(...) fails and the picker returns null → the add lambda's `if (id != null)` guard skips the add. In-place row combos work because their actionListener reads selection live (no JOptionPane round-trip).
Fix: replaced the picker with a filter JTextField + JList dialog (PokemonCardViewPanel.pickFromList) — list selection is unambiguous and maps back to the value by exact label. Also moveCombo now always includes the row's current move as an option (previously a move id not in the options list made the combo silently default to the first move, e.g. showing "1: Pound").
Reason: javac + Javac2 build clean; launch verified.
Next Step: trainer editor (or inline card evolution editing) per user.

## 2026-06-12 - Evolution reverse-link bug fixed + card/sheet data now shared (live sync)

Hypothesis: The evolutionsTo desync is fixable with a rebuild helper; the card/sheet "last writer wins" is fixable by sharing one map instance per data type.
Finding: True (both)
Evidence/how:
- Evolution fix: `EditorUtils.rebuildEvolutionReverseLinks(allSpecies)` (clears every evolutionsTo, rebuilds from the authoritative evolutionsFrom, restoring the shared-instance invariant). Wired via a TableModelListener on each evolution model (EvolutionsSheetPanel/Gen5/Gen6) so it fires on every setValueAt AND on restoreFromBackup's fireTableDataChanged. Gen3/4 inherit Gen5; Gen7 inherits Gen6. Consumers that read evolutionsTo (EvolutionRandomizer noConvergence filter, CopyUpEvolutionsHelper basic/split-evo detection, baby-walk loops) now see a consistent graph.
- Shared data: new `EditorDataCache` (per-RomHandler singleton like PokemonIconCache) lazily fetches ONE instance each of movesLearnt / eggMoves / tmhmCompatibility / moveTutorCompatibility. Card view + all sheets (Gen5/Gen6 learnsets, TMs, tutors, egg moves + dead base learnsets/TMs) now obtain their map from the cache instead of romHandler.getX(). Egg panels stopped deep-copying (copyEggMoves now unused). Result: edits in card and sheet are the SAME object → bidirectional live sync, no last-writer-wins. All sheet restores already mutate in place (verified) so reverts work on the shared instance.
- Why this works like types already did: Species fields were always shared (same live objects); the maps weren't (each getX() returns a fresh detached copy). The cache makes the maps shared too.
Reason: javac + Javac2 builds clean; launch verified.
Next Step: Optional — make card evolutions inline-editable now that the bug is fixed (currently the Evolutions sheet edits them and the card reflects live). Then the trainer editor.

## 2026-06-12 - Card View made fully editable + dark mode reworked + crisp icons

Hypothesis: The card view can become a full inline editor (sliders/fields/dropdowns/tags) writing back exactly like the sheets; dark mode can keep the "OG" look; blurry icons can be sharpened.
Finding: True
Evidence/!how:
- Dark mode: replaced Nimbus with Metal + a dark DefaultMetalTheme subclass (white/black flip + 6 core colors + Segoe UI fonts) in `gui/ThemeManager`. Smoke test: dark = all bg #3C3F41 / text #D6D8DA / Segoe UI 12; light = Windows LaF. Keeps classic boxy widgets/sizing (no Nimbus "slick squares"/font resize).
- Icons: `PokemonIconCache.getScaledIcon(species,size)` scales the NATIVE sprite with nearest-neighbour (no blur); card header uses 96px, list 36px, evo links 28px.
- Editable card (`PokemonCardViewPanel` rewrite): base stats = draggable bar + number field (live `Species` setters); EV yields/catch/exp/happiness/gender/hatch/callRate/color/height/weight = number fields; types/abilities/items/egg-groups/growth = dropdowns; TM-HM/tutors/egg moves = removable tags + "+ Add" pickers; level-up moves = editable level+move rows with add/remove. Evolutions kept read-only (avoid the known evolutionsTo-desync data-loss bug).
- Write-back contract (from deep investigation): Species fields persist live (no setter; main-window save reads live pokes[]); learnset/egg/TM/tutor need `romHandler.set...()` — card holds working copies, applies them in `save()` which the frame's Save All now calls LAST so card edits win for the data types it touched (dirty-flagged). Egg moves normalized (drop <0), learnset sorted by level. ManualEditRegistry section "Card View".
- Persistence model: edits live; "Save Changes" button (header) = frame.saveAll() commits (Personal sheet re-baselines shared Species; card pushes dirty maps); close-without-save reverts (Personal sheet restores Species; card map working-copies discarded).
- Known limitation (documented in UI): editing the SAME data type in BOTH the card and its sheet in one session → card wins (dirty-guarded so single-place edits are always correct).
Reason: Javac2 build clean; launch verified; theme colors verified by smoke test.
Next Step: Trainer editor (user's next request). Optional: share map instances between card and sheets to remove the same-type dual-edit limitation.

## 2026-06-12 - Session summary (trainer editor question, Card View, dark mode, bug hunt)

Hypothesis: See individual entries below.
Finding: Mixed (each entry carries its own verdict)
Evidence: Full deep-dive bug report with verified triggers and fixes: `docs/BUGHUNT_2026-06-12.md` (43 confirmed, 4 refuted).
Summary of what changed this session:
- NO trainer editor exists (README mention is aspirational; `Trainer`/`TrainerPokemon` + get/setTrainers make one feasible).
- NEW: Card View tab (read-only per-Pokemon card, all values) in all Gen 3-7 editor frames; 8 review findings fixed.
- NEW: Full dark mode - `gui/ThemeManager` (Settings menu toggle, persisted; dark Nimbus / native light) + `editors/EditorTheme`; 173 chrome-color conversions across 20 editor files.
- FIXED: type-chart defender column now frozen while scrolling; Edit Type Effectiveness button no longer clips; main tabs wrap instead of hiding behind scroll arrows.
- FIXED: version check no longer runs on the UI thread (`.run()` -> `.start()`) and fails quietly; launcher prefers modern Java 8 update builds (cert store).
- FIXED (from bug hunt): icon-cache RomHandler leak, preset null custom names, customnames overwrite-on-failed-load, combo-search hijacking JFileChooser, graphics tab stuck disabled, manual-edit log cross-ROM leak, guaranteed-item wipe, File>Close revert bypass (5 frames), status-combo no-op erase, egg-move -1 truncation, phantom moves row, TM log off-by-one, CSV UTF-8.
- BUILD: plain javac produces a crashing JAR (IntelliJ form weaving required) - use the Javac2 recipe; `launcher/UPR-FVX.jar` rebuilt this way (`UPR-FVX.jar.bak` = pre-session original).
Next Step: Remaining architectural findings (CSV ID-mapping, evolutionsTo desync, editor/randomizer lifecycle, batch EDT race, TE flag desync) are documented in `docs/BUGHUNT_2026-06-12.md` for a follow-up pass.

## 2026-06-12 - PokemonCardViewPanel review (API/data-contract lens)

Hypothesis: The new card view panel misindexes TM/HM/tutor compat arrays or miskeys learnset/egg-move maps vs the sheet panels.
Finding: False for indexing/keying (all match repo conventions: 1-based compat with HMs at tmCount+i, maps keyed by species.getNumber(), itemList/moveList/speciesList indexed by id); True for 4 smaller display bugs.
Evidence: RomHandler.java javadocs (getTMHMCompatibility, getItems, getSpecies), TMsSheetPanel, Gen5MoveTutorsSheetPanel, Gen5EggMovesSheetPanel, LearnsetsSheetPanel, EvolutionsSheetPanel, EvolutionType.java, Species.java, Gen3/4/5/6/7RomHandler setters, Gen3/4/5/6/7Constants, PokemonIconCache.java, EditorUtils.java.
Reason: Card view copied the sheet panels' conventions correctly; remaining issues are per-gen field semantics (getCallRate) and cosmetics.
Next Step: Fix the 4 reported items: callRate row gating per gen, 40px list cells vs 48px icons, white-on-gray fallback chips, dead evolution links for alt-forme/mega targets.

## 2026-06-12 - Card view: invisible white-on-gray chips for unknown/typeless moves (HM/tutor/egg sections)

Hypothesis: HM, tutor and egg-move chip sites in PokemonCardViewPanel pass Color.WHITE foreground even when background falls back to CHIP_GRAY, unlike the TM site which switches to TEXT_MUTED.
Finding: True
Evidence: PokemonCardViewPanel.java lines 742-743 (HM), 775-776 (tutor), 797-799 (egg) vs correct TM branch 723-725. Reachable triggers: (1) vanilla Gen 3/4 ???-type Curse (Gen3Constants/Gen4Constants typeTable[0x09] = null, loaded at Gen3RomHandler.java:648 / Gen4RomHandler.java:223) appears as an egg move; (2) Gen5/Gen6 EggMovesSheetPanel stores -1 for blanked cells and trimTrailingBlanks only strips trailing -1s, so mid-list -1 reaches setEggMoves -> lookupMove(-1) == null.
Reason: White text on 0xE9ECEF is ~1.2:1 contrast - chip renders as a blank gray pill.
Next Step: At the three sites compute boolean known = move != null && move.type != null; pass known ? Color.WHITE : TEXT_MUTED as chip foreground.

## 2026-06-12 - Card view forme gap (Gen 5-7) verified as design limitation, not bug

Hypothesis: Card view sidebar (PokemonCardViewPanel.fetchData line 95, getSpecies()) omits alt formes that Gen5/6/7 sheet panels (getSpeciesInclFormes()) can edit.
Finding: True as a factual gap, but classified NOT a runtime bug (panel is read-only base-form viewer by design; nothing malfunctions).
Evidence: PokemonCardViewPanel.java:95; Gen5PersonalSheetPanel.java:42, Gen6PersonalSheetPanel.java:48, Gen6LearnsetsSheetPanel.java:45 (Gen7 panels extend Gen6); RomHandler.java:109-111.
Reason: Review scope = real runtime bugs / visibly broken UI only; the only broken symptom (dead forme evolution links) is tracked as a separate claim.
Next Step: Optional enhancement - populate sidebar from getSpeciesInclFormes() for gen >= 5, render via EditorUtils.formatSpeciesDisplayName (exists at EditorUtils.java:81).

## 2026-06-12 - Card view: evolution links to Mega/Alolan formes clear sidebar selection

Hypothesis: navigateTo() in PokemonCardViewPanel assumes evolution-link targets are in the sidebar model, but Mega/Gen7-forme targets are forme Species absent from romHandler.getSpecies().
Finding: True
Evidence: PokemonCardViewPanel.java:95,183-188,256-265,223-232,562-573,601; Gen6RomHandler.java:447-452 (MegaEvolution -> pokes[absolute forme index]); Gen7RomHandler.java:447,1490-1502 (Evolution -> forme Species); Species.java:501-509 (equals by number); Gen6RomHandler.getSpecies() subList(0, pokemonCount+1).
Reason: Forme Species have number > pokemonCount, so listModel.contains() is false; JList.setSelectedValue falls through to setSelectedIndex(-1), clearing selection while the card still shows the old species; step() then treats index -1 as 0 and jumps to the first list entry. searchField.setText("") also wipes any active search.
Next Step: In navigateTo(), fall back to target.getBaseForme() (returns this for base formes) and bail out if still absent from the model.

## 2026-06-12 - PersonalSheetPanel family bug hunt (Gen5/Gen6 personal sheets)

Hypothesis: The fork's personal-sheet editors contain real data-loss bugs in column-to-field
mapping per generation, the SpeciesBackup lifecycle, and CSV import.
Finding: True
Evidence: Gen5PersonalSheetPanel.java, Gen6PersonalSheetPanel.java, EditorUtils.java,
Gen3/4/5/6 RomHandlers, Gen5EditorFrame.java, RandomizerGUI.java.
Reason: 8 confirmed issues: (1) Hatch Counter column dead on Gen3/4 (handlers persist callRate,
not hatchCounter; Gen4 never loads hatchCounter so column shows 0); (2) "Very Rare Held Item"
column dead on Gen6/7 (darkGrassHeldItem only persisted by Gen5RomHandler); (3) Gen5 panel
held-item edits clear guaranteedHeldItem without redistributing -> other slot wiped in saved ROM;
(4) CSV import applies rows positionally with no ID matching -> silent cross-species corruption;
(5) Gen6 Name edits (via CSV import) bypass SpeciesBackup -> survive Reload/close-without-save;
(6) editor frame non-modal + edits write directly into shared Species objects -> unsaved edits
baked into ROM if user saves from main window while editor open; (7) inner table model events
never reach the JTables (wrapper models) -> stale BST/Guaranteed Item cells; (8) Gen6 setValueAt
has no try/catch -> bad Type string in CSV aborts import halfway with raw enum error, partial apply.
Next Step: Fix (1)-(3) first (silent ROM corruption), then add ID matching to CSV import.

## 2026-06-12 - Deep bug hunt: editors package (EditorUtils, sheet panels, frames, icon cache)

Hypothesis: The fork's own editor code (pokemon/editors) contains real user-triggerable bugs in CSV import/export, save/restore lifecycle, combo-search wiring and cache lifetime.
Finding: True (8 confirmed bugs).
Evidence: EditorUtils.java (full read), Gen5PersonalSheetPanel.java setValueAt held-item cases 1069-1080, EvolutionsSheetPanel.java setValueAt 952-1060 + restoreFromBackup 348-369 (evolutionsTo never maintained), Gen3-7 EditorFrame close menu uses dispose() (skips windowClosing revert), PokemonIconCache.CACHE_BY_HANDLER static strong map (line 25), EditorUtils static enableGlobalComboBoxSearch (45-51, 509-534) hijacks all JComboBoxes incl. JFileChooser, applyCsvDataToTable positional row mapping (348-368), MovesSheetPanel/Gen5MovesSheetPanel getRowCount=movesList.size() includes null move 0 (phantom row), FileWriter/FileReader default charset in CSV export/import.
Reason: Each bug verified by tracing the exact code path to a concrete user trigger; Gen6PersonalSheetPanel contains the corrected held-item logic that Gen5 (used by Gen3/4 too) lacks.
Next Step: Fix in order: held-item wipe, evolutionsTo desync, File>Close dispatch WINDOW_CLOSING, WeakHashMap for icon cache, remove global combo-search AWT listener, ID-matched CSV row mapping.

## 2026-06-12 - CSV import is purely positional (no ID check) - confirmed real

Hypothesis:
applyCsvDataToTable maps CSV row N+1 to model row N with no ID/Name verification, so reordered/partial CSVs silently assign data to the wrong species.

Finding:
True

Evidence:
src/com/dabomstew/pkrandom/pokemon/editors/EditorUtils.java lines 332-368 (only header column COUNT validated, lines 342-346; positional apply lines 349-364; non-editable ID/Name skipped at line 358);
PersonalSheetPanel.java lines 471-505 (importFromCSV does no row-identity validation, success dialog "Imported %d rows"), line 818-820 (isCellEditable = col > 1, so ID/Name frozen), lines 69-74 + 426-435 (Save -> commitChanges -> createBackup overwrites backup, so Reload cannot recover after Save).

Reason:
Export writes rows in model order incl. ID/Name; a CSV sorted in Excel re-passes the column-count check (same 37 cols) and every editable value lands on the wrong row. maxRows = min(model rows, csv rows) silently truncates partial CSVs too. Same flaw in all 19 sheet panels calling applyCsvDataToTable.

Next Step:
Add a pre-validation pass in applyCsvDataToTable comparing csvRow[0] (ID) against model.getValueAt(rowIndex, 0) for every row BEFORE applying any (throw IllegalArgumentException naming the CSV line); existing catch blocks in each importFromCSV already surface it.

## 2026-06-12 - Hatch Counter column dead on Gen 3/4

Hypothesis: Personal Sheet "Hatch Counter" column is non-functional on Gen 3/4 (shows 0 on Gen 4, edits silently dropped on both).
Finding: True
Evidence: Gen4RomHandler.java:788 loads hatch cycles into setCallRate only (no setHatchCounter anywhere in file); :985 saves from getCallRate(). Gen3RomHandler.java:1163-1164 loads both, :1222 saves from getCallRate(). Gen3/Gen4PersonalSheetPanel are empty subclasses of Gen5PersonalSheetPanel whose COL_HATCH_COUNTER reads/writes Species.hatchCounter only; column always present and editable; save() shows "will be saved" dialog.
Reason: Species.hatchCounter never reaches the ROM byte on Gen 3/4; Gen 4 never even populates it (int default 0).
Next Step: Mirror Gen6/Gen7: load setHatchCounter in Gen4 loadBasicPokeStats, write getHatchCounter() at Gen4RomHandler.java:985 and Gen3RomHandler.java:1222 (generic PersonalSheetPanel using callRate is dead code, never instantiated).

## 2026-06-12 - Gen5 panel held-item edits wipe other slot for guaranteed-item species

Hypothesis:
Gen5PersonalSheetPanel.setValueAt nulls guaranteedHeldItem without redistributing it, so editing one item column destroys the other slot on ROM save (Gen3/4/5).

Finding: True

Evidence:
- src/com/dabomstew/pkrandom/pokemon/editors/Gen5PersonalSheetPanel.java:1069-1080 (COL_COMMON/RARE/DARK_GRASS_ITEM each do setGuaranteedHeldItem(null) then set only their own slot)
- Load paths set ONLY guaranteed when both ROM slots match: Gen5RomHandler.java:514-525, Gen4RomHandler.java:776-785, Gen3RomHandler.java:1147-1159
- Write paths zero null slots when guaranteed==null: Gen5RomHandler.java:805-816, Gen4RomHandler.java:974-982, Gen3RomHandler.java:1207-1218
- Gen3/Gen4PersonalSheetPanel are trivial subclasses (no overrides); isCellEditable (line 897-902) allows all item columns incl. Dark Grass on Gen3/4 where it is never persisted -> editing it wipes BOTH slots
- Gen6PersonalSheetPanel.java:1081-1103 handles this correctly (moves old guaranteed into untouched slot)

Reason:
Live Species objects are mutated directly; panel Save commits them; randomizer ROM save then writes 0 into the null slot(s).

Next Step:
Mirror Gen6 logic in Gen5PersonalSheetPanel.setValueAt: copy old guaranteed into untouched slot(s) before clearing it (both common+rare for the dark-grass case).

## 2026-06-12 - RandomizerGUI + RomOpener deep bug hunt (incl. error_2025-11-01 crash root cause)

Hypothesis: error_2025-11-01-06-34-10.txt shows a GBA FireRed being misdetected as Gen 4; plus fork-added editor/TE wiring in RandomizerGUI has lifecycle bugs (stale handler, lost edits).
Finding: Partially true (misdetection hypothesis False; lifecycle bugs True)
Evidence: NARCArchive.readNitroFrames:194-195 (unvalidated frame_size -> AIOOBE, matches stack); RomOpener.openRomFile:118-127 (only EncryptedROMException caught); AbstractDSRomHandler.getROMCodeFromFile reads bytes 0x0C-0x0F (in any real GBA these are fixed Nintendo-logo bytes 3D 84 82 0A -> can never match a Gen4 code); attemptToLogException prints romHandler (the PREVIOUSLY loaded FireRed) into the log, never the file being opened. So the crash was a second, corrupt NDS-coded file; the log header misattributes it to FireRed. Other confirmed bugs: editor frames keep stale RomHandler after randomize (finishRandomization unload/reinitializeRomHandler), blanket restoreFromBackup on windowClosing reverts committed edits (multi-frame / Card View), TE manual-edit flag desyncs both directions, failed open leaves stale romHandler with GUI saying "No ROM loaded", batch randomization EDT/worker race on romHandler, graphics tab (index 8) never re-enabled, error logger NPE when romHandler==null + System.err leak.
Reason: Verified by reading current working-tree code and git history; stack trace lines match exactly.
Next Step: Guard romHandler.loadRom in RomOpener (catch RomIOException/RuntimeException -> failure result), bounds-check readNitroFrames, dispose/track editor frames on ROM unload/reload, fix initialState/reportOpenRomFailure to null handler + disable editor buttons.

## 2026-06-12 - Adversarial re-verify: File > Close bypasses revert-on-close (confirmed real, still unfixed)

Hypothesis: closeItem -> dispose() skips windowClosing, so uncommitted sheet edits survive into the saved ROM.
Finding: True
Evidence: Gen5EditorFrame.java:42 (DISPOSE_ON_CLOSE), 45-57 (revert only in windowClosing), 112 (dispose()); same in Gen3:94, Gen4:110, Gen6:112, Gen7:99. Gen5MovesSheetPanel.java:158 binds live romHandler.getMoves() (Gen5RomHandler.java:1023 Arrays.asList(moves)); setValueAt (~1390-1430) mutates live Move fields; onWindowClosing() is the only revert path. RandomizerGUI.java:4084 passes the same romHandler instance used for randomize/save. No dispose() override or windowClosed handler exists in the editors package.
Reason: Window.dispose() fires WINDOW_CLOSED, never WINDOW_CLOSING (standard AWT), so no panel restoreFromBackup() runs on the File > Close path.
Next Step: In all five frames replace dispose() with dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)).

## 2026-06-12 - Moves Sheet Status Type no-op commit erases statusEffect (Gen5/Gen6)

Hypothesis: Committing the displayed "None" in the Status Type column overwrites statusEffect ids >= 8 (Taunt=11 etc.) with 0.
Finding: True
Evidence: Gen5MovesSheetPanel.java:1476-1485 / Gen6MovesSheetPanel.java:1509-1518 (unconditional move.statusEffect = statusType.ordinal(), no change guard in setValueAt 1390-1398); StatusType.java (8 constants, 0-7); Gen5RomHandler.java:442-464 (statusType=NONE for raw >= 8) and :608 (writes statusEffect word); Gen6RomHandler.java:636,952; StatusComboBoxEditor = DefaultCellEditor(JComboBox) so stop-edit always calls setValueAt; getMoves() returns live Arrays.asList(moves); save() commitChanges() rebases backup so Reload cannot restore.
Reason: Display is lossy (statusEffect 8-21/0xFFFF all render as "None"); committing the rendered value writes NONE.ordinal()=0 into the live Move, and saveMoves persists it at move-data offset 8.
Next Step: Add early-out in COL_STATUS_TYPE case when parsed StatusType equals move.statusType, in both Gen5 and Gen6 panels.

## 2026-06-12 - CSV import maps rows by position, ignores ID column

Hypothesis: applyCsvDataToTable applies CSV rows positionally with no ID validation, so reordered/row-deleted CSVs silently corrupt data.
Finding: True
Evidence: src/com/dabomstew/pkrandom/pokemon/editors/EditorUtils.java:348-367 (positional loop, only column-count check at 343); PersonalSheetPanel.java:818-820 (ID/Name non-editable, so skipNonEditable=true silently skips CSV ID), 1097-1105 (parseInt fallback to 0); 18 sheet-panel call sites all pass skipNonEditable=true with no upstream ID check.
Reason: CSV row N+1 is written to model row N unconditionally; the ID column is skipped via the non-editable continue, short rows are null-padded, and callers report "Imported N rows" success.
Next Step: In applyCsvDataToTable, build Map<id, modelRow> from model.getValueAt(r,0) and route each CSV row by its ID; throw IllegalArgumentException (callers already show it as an Import Error dialog) on missing/duplicate IDs.

## 2026-06-12 - Gen3/4/5 Personal Sheet CSV import wipes Guaranteed Held Items

Hypothesis: Unconditional setGuaranteedHeldItem(null) in COL_COMMON/RARE/DARK_GRASS cases of Gen5PersonalSheetPanel.setValueAt wipes guaranteed items on CSV round-trip and on single-cell re-confirm.
Finding: True
Evidence: Gen5PersonalSheetPanel.java:1069-1080 (unconditional null), :804-807 (col order 19<20<21<22), :897-902 (all item cols editable), :1142-1144 (null renders "None"), :1265-1267 (findItem("None")==null); EditorUtils.java applyCsvDataToTable ascending column loop; Gen5RomHandler.java:517-525/805-815, Gen4RomHandler.java:781/974-976, Gen3RomHandler.java:1155/1207 (guaranteed = common==rare, save writes 0s when all null); Gen3/Gen4 panels extend Gen5 panel directly; Gen6PersonalSheetPanel.java:1081-1107 has the corrected split/recombine logic.
Reason: Export renders common/rare/dark as "None" for guaranteed-item species; import applies col 19 (sets guaranteed) then cols 20-22 which each null it unconditionally, leaving all four item fields null; save then writes item id 0 to ROM. JTable also calls setValueAt on every edit commit, so confirming the "None" Common cell alone wipes the guaranteed item.
Next Step: Guard the three cases - only clear guaranteed when findItem(...) returns non-null (mirror Gen6 split logic).

## 2026-06-12 - File > Close skips unsaved-edit revert in all Gen editor frames

Hypothesis: File > Close calls dispose() directly, which never fires WINDOW_CLOSING, so the windowClosing revert (onWindowClosing -> restoreFromBackup) is skipped and abandoned edits stay in the live RomHandler.
Finding: True
Evidence: Gen5EditorFrame.java:45-57 (WindowAdapter overrides only windowClosing), :112 (closeItem -> dispose()); Gen5PersonalSheetPanel.java:42 (live romHandler.getSpeciesInclFormes()), :70-73 (edits applied directly to Species), :488-498 (onWindowClosing -> restoreFromBackup, only other caller is reload()); same dispose() pattern at Gen3EditorFrame.java:94, Gen4EditorFrame.java:110, Gen6EditorFrame.java:112, Gen7EditorFrame.java:99; RandomizerGUI.java:4084 shares the same romHandler used for randomize/save.
Reason: AWT Window.dispose() posts WINDOW_CLOSED only; WINDOW_CLOSING is fired solely by a window-system close request (title-bar X). No dispose() override or windowClosed handler compensates.
Next Step: In each Gen*EditorFrame.createMenuBar(), replace dispose() with dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)) so DISPOSE_ON_CLOSE handles disposal after the revert runs.

## 2026-06-12 - CSV export/import uses platform-default charset (EditorUtils)

Hypothesis: FileWriter/FileReader in EditorUtils CSV export/import mangle U+2640/U+2642 (Nidoran) and mis-decode UTF-8 CSVs on Windows/Java 8.
Finding: True
Evidence: src/com/dabomstew/pkrandom/pokemon/editors/EditorUtils.java line 159 (new FileWriter(path)), line 299 (new FileReader(selected)), stripBom lines 1132-1137 expects U+FEFF (only possible under UTF-8 decode); .tbl text tables (gba_english.tbl B5=male/B6=female etc.) prove gender symbols reach table models; MovesSheetPanel.java lines 813-815 + 918-919 show Name column is editable and import writes move.name back to ROM data. No charset specified anywhere in the file.
Reason: On Windows Java 8 default charset is cp1252; the encoder replaces unmappable U+2640/U+2642 with '?', and UTF-8 input is decoded as cp1252 mojibake which can be committed to ROM via Moves-sheet import. stripBom is dead code under cp1252 (BOM bytes arrive as three chars).
Next Step: Replace with OutputStreamWriter/InputStreamReader using StandardCharsets.UTF_8 in exportTableToCSV and chooseCsvFile.

## 2026-06-12 - ManualEditRegistry never cleared (cross-ROM log leak) - CONFIRMED

Hypothesis: ManualEditRegistry singleton accumulates edits across ROM loads; a later ROM's Save All shows/saves the previous ROM's edits under the new ROM's name.
Finding: True
Evidence: src/com/dabomstew/pkrandom/log/ManualEditRegistry.java (clear() at line 64 has zero callers, project-wide grep); Gen5EditorFrame.java:132-135 (snapshot + dialog with romHandler.getROMName()); ManualEditLogDialog.java:55-80 (Save Log does not clear); RandomizerGUI.java:928-941, 2743 (romHandler reassigned on open, initialState()/romLoaded() never touch the registry).
Reason: Process-wide singleton, entries added in every panel save(), no clear on ROM (re)load or after log save; section names ("Moves Data" etc.) are not ROM-scoped.
Next Step: Call ManualEditRegistry.getInstance().clear() right after romHandler = results.getRomHandler() in RandomizerGUI (or at start of romLoaded()).

## 2026-06-12 - Evolutions Sheet editors desync evolutionsTo graph

Hypothesis: EvolutionsTableModel.setValueAt and restoreFromBackup mutate only evolutionsFrom, leaving Species.evolutionsTo stale, and the randomizer consumes the stale graph.
Finding: True (one caveat: EvolutionRandomizer.clearEvolutions() wipes both lists for pool species, so the noConvergence filter sub-trigger largely self-heals; all other consumers are affected).
Evidence: EvolutionsSheetPanel.java:952-1060 (evos.remove at 975/1009, evos.add at 984/995, evo.setTo at 989), restoreFromBackup 348-369 + onWindowClosing 375-378; Species.java:1066-1080 (live independent lists); Evolution.java setTo/ctor (no back-ref maintenance); to-lists only populated at ROM load (e.g. Gen4RomHandler.java:4266/4285); editor and GameRandomizer share the same romHandler (RandomizerGUI.java:4045-4131, 1232); stale consumers: TrainerMovesetRandomizer.java:598-650, CopyUpEvolutionsHelper.java:179-247, Gen4RomHandler.java:5329-5331, EvolutionRandomizer.java:195. Duplicated model in Gen5EvolutionsSheetPanel.java (~954-1011) and Gen6EvolutionsSheetPanel.java (~1196-1273).
Reason: setTo retargets the SAME Evolution instance still held in the old target's evolutionsTo; removals/additions never touch to-lists; restoreFromBackup replaces from-list entries with fresh copies on every editor close so list instances diverge.
Next Step: Maintain both sides in setValueAt and rebuild all to-lists from from-lists at the end of restoreFromBackup, in all three panel copies.

## 2026-06-12 - Reinit after save discards manual TE/editor edits while UI says Custom

Hypothesis: finishRandomization()/batch reinitializeRomHandler() reloads the ROM from disk and silently drops manual TypeTable + Gen-editor edits (which live only in the RomHandler), while typeEffectivenessManuallyEdited stays true and the button still reads "Custom".
Finding: True
Evidence: RandomizerGUI.java - openRom(f, reinitialize=true) skips initialState()/romLoaded() (lines 930-937) but replaces romHandler (934); finishRandomization keep-loaded path calls reinitializeRomHandler(false) (1321-1326); batch path reinitializes after EVERY ROM (1206-1208 inside performRandomization, loop at 1063-1082); the custom table is only stored via romHandler.setTypeTable (3914) with no GUI-side copy; flag resets only in initialState (2422), romLoaded (3122), restoreStateFromSettings (1654) - none run on reinit. Gen3-7 editor frames (4045/4065/4084/4103/4130) keep references to the discarded handler.
Reason: Requires "Keep game loaded after randomizing" toggled (default unloadGameOnSuccess=true, line 4314) OR batch mode (reachable at defaults). Then save #2..N is built from a fresh vanilla handler while UI claims Custom.
Next Step: In openRom reinitialize success path, capture new TypeTable(romHandler.getTypeTable()) when typeEffectivenessManuallyEdited and re-apply to the fresh handler; track/dispose open Gen editor frames on handler replacement.

## 2026-06-12 - Corrupt DS ROM crash + wrong-ROM error log (error_2025-11-01-06-34-10.txt) verified REAL

Hypothesis: Three stacked defects: NARCArchive.readNitroFrames has no bounds checks, RomOpener only catches EncryptedROMException around loadRom, and attemptToLogException blames the previously loaded romHandler instead of the file being opened.
Finding: True (all three)
Evidence: NARCArchive.java:176-199 (readWord(data,0x0E), new byte[frame_size-8], arraycopy at :195 = exact AIOOBE frame in error_2025-11-01-06-34-10.txt); RomOpener.java:118-127 (only catch EncryptedROMException; RomIOException.java:30 extends RuntimeException); RandomizerGUI.java:942-947 generic catch -> attemptToLogException:2330-2346 prints this.romHandler (set only on success at :934), never File f; error file header = FireRed BPRE/.gba but stack = Gen4RomHandler (impossible self-crash: GBA bytes 0x0C-0x0F are fixed logo bytes 3D 84 82 0A, never a Gen4 code, and Gen3 factory precedes Gen4 in FACTORIES).
Reason: No upstream validation (detectInvalidROM checks only 10-byte ZIP/RAR/PATCH sigs); AbstractDSRomHandler.loadRom calls loadedROM outside its IOException try; Gen4RomHandler.loadedROM:124-138 wraps IOException in RomIOException which RomOpener does not catch.
Next Step: Apply 3-part fix: catch RomIOException/RuntimeException in RomOpener.openRomFile -> FailType; bounds-validate readNitroFrames (data.length>=0x10, frame_size>=8, offset+frame_size<=data.length -> IOException "Not a valid narc file"); log f.getName() in RandomizerGUI.openRom catch.

## 2026-06-12 - Failed ROM open leaves stale romHandler active behind "No ROM loaded" UI

Hypothesis: openRom's failure branch resets the UI via initialState() but never nulls romHandler, so Randomize (Save) and the fork's genX editor buttons still operate on the previously loaded ROM.
Finding: True
Evidence: RandomizerGUI.java openRom lines 928-941 (initialState() at 931 runs unconditionally; failure branch 938-940 only calls reportOpenRomFailure, romHandler keeps old handler; catch block 942-948 same omission); initialState() 2414-2648 re-enables randomizeSaveButton at 2432 and never touches gen3..gen7EditorButton; saveROM() line 994 only guards romHandler == null; editor buttons set only in constructor (disabled, 3964/3984) and romLoaded() 3206-3210; openGen3Editor() 4025-4050 passes both guards with the stale handler. Trigger reachable: File > Open ROM with a .zip returns Results failure INVALID_ZIP_FILE (handled at 966).
Reason: Neither failure path nulls the handler, and the editor buttons are outside initialState()'s reset set, so UI state and model state diverge.
Next Step: Null romHandler in both failure paths of openRom and disable gen3..gen7EditorButton in initialState().

## 2026-06-12 - Graphics tab (index 8) stuck disabled after loading a 3DS ROM

Hypothesis: romLoaded() disables tab 8 one-way with no re-enable path, so a 3DS load permanently greys out Graphics for the session.
Finding: True
Evidence: src/com/dabomstew/pkrandom/gui/RandomizerGUI.java:3157-3159 (disable-only); whole-src grep shows only two setEnabledAt calls (3111 for tab 7 two-way, 3158 for tab 8 disable-only); initialState() (line 2414) never touches tabbedPane1; form file confirms index 8 = "Graphics"; Abstract3DSRomHandler/Gen6/Gen7 inherit false from AbstractRomHandler:726/736 while AbstractGBRomHandler:180 and AbstractDSRomHandler:519 return true.
Reason: Loading Gen 6/7 hits the if-branch and disables tab 8; loading Gen 1-5 afterwards skips the branch entirely, leaving the stale disabled state. initialState() between loads does not reset it.
Next Step: Replace the conditional with tabbedPane1.setEnabledAt(8, ppalSupport || cpgSupport);

## 2026-06-12 - Batch randomization EDT race on romHandler (verified real)

Hypothesis: Per-ROM finishRandomization (queued on EDT) unloads/reloads romHandler concurrently with the batch SwingWorker loop, racing the worker's own reinitializeRomHandler.
Finding: True
Evidence: RandomizerGUI.java - batch worker loop 1056-1104 (done() at 1086 never calls get()); performRandomization 1206-1208 (t.join() then reinitializeRomHandler(true) inside try/catch at 1210); performRandomizationInner 1240 queues finishRandomization on EDT; finishRandomization 1321-1326 unconditionally nulls romHandler (unloadGameOnSuccess default true at 4314) or spawns a second openRom; reinitializeRomHandler 1634 dereferences romHandler.loadedFilename(); openRom 928-941 assigns romHandler only in a later invokeLater, so t.join() at 1637 does not guarantee the assignment; romHandler is a plain non-volatile field (398).
Reason: No batch guard on finishRandomization's trailing unload block; three call sites mutate romHandler from two threads with no synchronization. EDT nulling between iterations -> NPE at 1634 (caught, logged save-failed, but reload never queued) -> NPE at 1069 next iteration (uncaught, swallowed by SwingWorker) -> batch stops yet pops "randomization done". Even without losing the race, the join-only-waits-for-the-opener-thread gap lets the next iteration start with a stale/null handler.
Next Step: Guard finishRandomization's unload/reinit block with !batchRandomization, and make reinitializeRomHandler(true) call romOpener.openRomFile synchronously on the worker thread.

## 2026-06-12 - Type-effectiveness "manually edited" flag desyncs from real table (both directions)

Hypothesis: typeEffectivenessManuallyEdited (RandomizerGUI) can disagree with the actual TypeTable in the RomHandler in both directions.
Finding: True
Evidence: RandomizerGUI.java lines 3821-3822 (flag cleared, no table revert), 1654 (restoreStateFromSettings clears flag, same handler kept), 1126-1147 (loadQS calls initialState+romLoaded on the SAME handler, clearing flag at 2422/3122 without revert), 3914-3920 (setTypeTable applied immediately + flag set), 917-952 (openRom reinitialize=true skips initialState/romLoaded so flag survives handler replacement), 1321-1326 (finishRandomization -> reinitializeRomHandler(false) when keep-loaded), Gen3RomHandler.java 4225-4239 (setTypeTable writes straight into rom[] bytes), GameRandomizer.java 313-329 (TE UNCHANGED leaves table as-is).
Reason: Direction 1: edit chart -> pick a non-Unchanged TE radio (or load a .rnqs) -> flag cleared but custom table stays baked in handler ROM bytes; back on Unchanged the saved ROM has the custom chart while button says "default". Direction 2: with Keep Game Loaded, after randomize+save the handler is reloaded from disk (stock table) but flag stays true, so button says "Custom (edited)" while the next save writes the vanilla chart.
Next Step: Snapshot original table before first edit, store edited table in a GUI field; restore original when clearing the flag, re-apply edited table (or clear flag+label) after reinitialize in openRom's reinitialize branch.

## 2026-06-12 - Gen6 Personal Sheet CSV import: unguarded setValueAt half-applies import on bad Type string

Hypothesis: Gen6PersonalSheetPanel.setValueAt lacks the try/catch the Gen5 panel has, so a non-uppercase Type in an imported CSV aborts the import mid-loop with a raw enum error and no rollback.
Finding: True
Evidence: Gen6PersonalSheetPanel.java setValueAt lines 1005-1158 (no try/catch; Type.valueOf at 1047/1052); Gen5PersonalSheetPanel.java lines 999-1135 (whole switch wrapped in try/catch(Exception)); EditorUtils.java applyCsvDataToTable lines 348-368 (per-cell model.setValueAt, no guard; coerceValue passes strings through for Object.class columns); Gen6 importFromCSV lines 560-578 (catch IllegalArgumentException shows raw ex.getMessage()); Type.java line 37 (uppercase constants only); isCellEditable line 902-904 (type columns editable).
Reason: Type.valueOf("Fire") throws IllegalArgumentException which propagates out of the import loop into importFromCSV's catch; rows already processed stay mutated (Species objects edited directly), no rollback, error message is the raw "No enum constant" text.
Next Step: Wrap Gen6 setValueAt switch in try/catch like Gen5 and parse types with trim().toUpperCase(Locale.ROOT); ideally pre-validate CSV cells before mutating and report the failing row number. Check Gen3/4/7 panels for the same pattern.

## 2026-06-12 - PokemonIconCache static map leak verified (with fix caveat)

Hypothesis: CACHE_BY_HANDLER (PokemonIconCache.java:25) pins every RomHandler forever; OOM after repeated load-ROM/open-editor cycles.
Finding: True (severity slightly overstated for NDS; proposed WeakHashMap fix insufficient as written).
Evidence: PokemonIconCache.java:25,27,44 (static ConcurrentHashMap, instance strongly holds romHandler, computeIfAbsent with no remove/clear anywhere - only 2 refs to CACHE_BY_HANDLER in repo); 14 editor panels call PokemonIconCache.get(romHandler); RandomizerGUI.java:1354 replaces romHandler per load (old handler otherwise unreachable); no equals/hashCode override in any RomHandler (identity keys, one entry per load); AbstractGBRomHandler holds rom+originalRom byte[]; AbstractDSRomHandler holds arm9 + loaded NARCs (NDSRom extracts file data to disk tmp dir, so per-NDS-handler in-memory cost is tens of MB, not "hundreds").
Reason: Unbounded static strong map keyed by per-load handler instances with zero eviction = real leak; growth per cycle is real even if per-ROM size claim is high.
Next Step: Fix must NOT be plain WeakHashMap alone - the value (PokemonIconCache) strongly references its key via the romHandler field, defeating WeakHashMap. Either make the field a WeakReference<RomHandler> + synchronized WeakHashMap, or simpler: keep a single static current-cache entry and evict when a different handler is passed to get().

## 2026-06-12 - TE manually-edited flag desyncs from handler type table (both directions)

Hypothesis: Bug-hunter claim that typeEffectivenessManuallyEdited desyncs from the real TypeTable in both directions.
Finding: True
Evidence: RandomizerGUI.java lines 3824-3826 (flag cleared, no table revert), 1657, 3125 (same), 3917 (setTypeTable applied permanently; Gen3RomHandler.writeTypeTable mutates rom[] directly), 1328 -> reinitializeRomHandler -> openRom(f, true) lines 933-940 (skips initialState/romLoaded, flag survives handler replacement), GameRandomizer.maybeRandomizeTypeEffectiveness line 314 (UNCHANGED never rewrites table).
Reason: No code path reverts the handler table when the flag clears, and no code path clears/re-applies the flag when the handler is rebuilt from disk in keep-loaded mode.
Next Step: Snapshot original table before first edit; revert on flag clear; re-apply (or clear flag) after reinitializeRomHandler.

## 2026-06-12 - CustomNamesEditorDialog Save destroys unreadable customnames file

Hypothesis: When the customnames file fails to load (wrong version/corrupt), Save writes empty lists over the user's file.
Finding: True
Evidence: src/com/dabomstew/pkrandom/gui/CustomNamesEditorDialog.java (ctor catch lines 64-67 only shows a dialog; save() lines 120-137 writes text areas unconditionally); src/com/dabomstew/pkrandom/customnames/CustomNamesSet.java lines 187-188 and 212-213 throw IOException for wrong version byte / bad block size; RandomizerGUI.java:632 opens the dialog with no upstream validation.
Reason: On IOException the five JTextAreas stay empty, no flag is set, saveBtn stays enabled; save() serializes the empty areas and overwrites the file with no backup/confirmation.
Next Step: Add a loadFailed flag set in the catch block; in save(), confirm overwrite (or disable saveBtn) and optionally write a .bak copy first.

## 2026-06-12 - Applied 7 verified Group B bug fixes (editors)

Hypothesis: The 7 verifier-approved fixes from fixes_groupB.json apply cleanly despite the EditorTheme/TableLayoutDefaults refactor.
Finding: True
Evidence: Gen5PersonalSheetPanel (guaranteed-item wipe, Gen6 already correct), all 5 Gen*EditorFrame Close items (dispose -> WINDOW_CLOSING dispatch), Gen5/Gen6MovesSheetPanel COL_STATUS_TYPE no-op guard, Gen5/Gen6EggMovesSheetPanel trimTrailingBlanks now drops ALL negative slots, MovesSheetPanel + Gen5/Gen6MovesSheetPanel row models now 1-based (phantom row 0 gone), Gen5/Gen6/base TMsSheetPanel log loop now diffs 1..totalColumns with label i-1, EditorUtils CSV now explicit UTF-8 (no BOM).
Reason: Code at each site matched the review blueprints; line numbers had drifted only slightly. Subclass chains (Gen3/4 -> Gen5, Gen7 -> Gen6) confirmed, so single-point fixes cover all generations.
Next Step: Coordinator compiles; then smoke-test CSV round-trip and File > Close revert in the editor.

## 2026-06-13 - Audit (logging_save): Load-Settings clears manual-edit log registry

Hypothesis: the manual-edit logging + Save-All suppression flow has a bug in flag reset, registry clear timing, or duplicate/omitted log entries.
Finding: Partially true (one substantiated bug; the rest of the flow is correct).
Evidence:
  - EditorUtils.suppressSaveDialogs: every saveAll() in Gen3-7 EditorFrame uses try/finally to reset it; all sheet panels guard their confirmation dialog with `if (!EditorUtils.suppressSaveDialogs)`; runs on EDT only (no reentrancy). Correct.
  - Duplication: every sheet panel re-baselines via commitChanges()->createBackup() after collectChangesForLog(); Card View + Trainer panels use a LinkedHashSet editLog and clear() it after addEntries(). Repeated Save All does not duplicate. Correct.
  - BUG: RandomizerGUI.loadQS() (line ~1156) loads a .rnqs SETTINGS file and calls initialState(); romLoaded();. romLoaded() (line 2759) calls ManualEditRegistry.getInstance().clear() but does NOT reload Species/trainers. So manual edits stay applied in memory while the registry is wiped -> RandomizationLogger.logManualEdits() writes an empty "Manual Edits" section even though the edits ARE in the saved ROM.
Reason: romLoaded() conflates "a new ROM was opened" (presetLoader/open-ROM, where a clear is correct) with "settings reloaded for the same ROM" (loadQS, where the in-memory edits + their log entries must survive).
Next Step: Move ManualEditRegistry.clear() out of romLoaded() into the actual open-ROM/preset paths only (or guard it so loadQS does not clear), so loading a settings preset before randomizing keeps the manual-edit log.

## 2026-06-16 - Editor CSV-import corruption + level-curve scaling fixes

Hypothesis: confirmed bugs in learnset/evolution CSV import and the trainer level-curve dialog corrupt ROM data or produce wrong curves.
Finding: True (all four reproduced from the code paths and fixed).
Evidence: LearnsetsSheetPanel/Gen5LearnsetsSheetPanel.setValueAt + save(); EvolutionsSheetPanel/Gen5EvolutionsSheetPanel.setValueAt + save() + reverse-link listener; EditorUtils.applyCsvDataToTable; gui/TrainerLevelCurveDialog.buildCurvePoints/remapLevel/slider sync.
Fixes:
- BUG1 (learnsets): setValueAt no longer extends a learnset for empty/0 move (or from a level edit); save() writes a normalized copy (createNormalizedLearnsets) dropping move<=0 entries. A no-op CSV round-trip no longer balloons every moveset to 20x "move 0 @ lv 0".
- BUG2 (evolutions): mod==2 (Parameter) branch no longer creates an evo slot when none exists; new EditorUtils.stripNoneEvolutions() drops EvolutionType.NONE evos. Called at save() (+ rebuildEvolutionReverseLinks) and on full-table refresh in the model listener (NOT on single-cell edits, which legitimately create a NONE slot for the in-progress row).
- BUG3 (CSV import identity): EditorUtils.applyCsvDataToTable now matches rows by leading-numeric ID when col0 header == "ID" (falls back to positional otherwise), SKIPS short rows and unmatched-ID rows instead of padding/positional-applying, and surfaces a skipped-count warning dialog via a new 4-arg overload (parent Component). Old 3-arg signature preserved for the other 16 callers.
- BUG4 (level curve): buildCurvePoints merges tied-ace milestones keeping the higher target (was TreeMap last-wins) and enforces a non-decreasing target sequence (running max) so remapLevel can't invert; Scale slider re-syncs to neutral 1.00x after Normalize/Even-ramp/manual spinner edits (guarded by `adjusting`) so a later 1-tick nudge no longer silently discards hand edits.
Reason: each matched the described corruption/correctness path; all six changed files + dependent subclasses compile clean on JDK 1.8 (exit 0, only pre-existing unchecked notes).
Next Step: manual UI smoke-test of a no-op CSV round-trip on learnsets + evolutions, and a tied-ace level-curve scale-all, to confirm runtime behavior.

## 2026-06-16 - Audit #3 (third bug hunt): move-encode, card caps, editor lifecycle, log dedup

Hypothesis: a third deep audit (bugs / usability / ROM-patch integrity / scaling / gym-leader roles) surfaces more confirmed defects beyond the CSV + level-curve set.
Finding: True - 21 issues confirmed by a multi-agent fan-out + adversarial verify; the high/medium-confidence ones are fixed below, the rest are documented as deferred.
Evidence: Gen6RomHandler.saveMoves (~963), Gen7RomHandler.saveMoves (~993), PokemonCardViewPanel (Pokedex Color row ~998), MoveCardViewPanel (Trap Move flag ~651), TrainerEditorPanel (IV row ~565), RandomizerGUI (performRandomization ~1206 / finishRandomization "Done" ~1338 / romLoaded ~2759), ManualEditRegistry.addEntry/addEntries.
Fixes (this pass, hand-applied):
- ROM WRITE-BACK (data corruption): Gen6 & Gen7 move save() wrote recoilPercent into the absorb byte and double-wrote bytes. Now: if categoryQuality == damageAbsorbQuality -> data[18]=absorbPercent (clamped 0..255), else data[18]=clampSigned(-recoilPercent,-128,127); target written to data[20]. Drain/recoil moves now round-trip correctly.
- CARD CAPS (invalid values written): Pokedex Color spinner was 0..255; real range is gen3 0..127, gen6+ 0..63. Capped via colorMax. Trap Move checkbox in the Move card was editable but is a derived field (min/max trap turns + effect index) -> set disabled with an explanatory tooltip so a user can't set an inconsistent flag.
- TRAINER IVs (Gen7 shiny bit): Gen7 packs IVs in a 32-bit field whose bit 30 = shiny. The editor wrote the raw 0..31 spinner over the whole field, wiping shininess. Now reads/writes only the low 5 bits on Gen7 (tp.getIVs() & 0x1F), preserving the rest; other gens unchanged (Gen6 still syncs the 'strength' byte).
- EDITOR LIFECYCLE (stale handler / race / log leak): added RandomizerGUI.closeOpenEditorFrames() (disposes any open Gen3-7 *EditorFrame via Window.getWindows(); dispose() does not fire windowClosing so no panel-revert runs and live edits already in the handler survive). Called at performRandomization start (kills the background-randomize-vs-open-editor race + prevents an editor editing a handler about to be replaced) and at romLoaded start (a new ROM/preset invalidates editors for the previous handler). After a randomize log is produced, finishRandomization now clears ManualEditRegistry so the edits (already baked into the saved+reloaded ROM) don't re-appear as "manual edits" in a later run's log.
- LOG DEDUP (#9, both save paths): ManualEditRegistry.addEntry/addEntries now skip a line already present in that section. Each edit line is unique per section (it names the entity+field), so an identical line only arises from re-emitting the same edit (clicking a panel's own Save twice, or Save All after an individual Save). Sheet panels re-emit a full diff each save and card panels drain an editLog; dedup-on-add makes BOTH paths idempotent without touching any panel.
Reason: each fix matches a confirmed code path; full CLEAN Javac2 build (rm -rf out first) of 281 files succeeded, javap -p shows no duplicate woven methods on RandomizerGUI / Gen3/5/7 EditorFrame, and a class-load probe force-loads all form-woven classes (no ClassFormatError) + verifies dedup (2 lines after a double add) and registry clear.
Deferred (documented, not yet changed): #4 CSV quoted-newline parsing; #12 editor constructed on EDT (brief freeze on large ROMs); #13 sprite decode on EDT during scroll; #14 card view edits Species live but never reverts on close (contradicts the hint text); #20 randomizers overwrite manual edits while the log still claims them; #21 type-effectiveness manual-edit flag not reset on reinitialize; (from Audit #2) SM/USUM kahuna-who-is-also-E4 bundled into the Grand Trial label.
Next Step: user runs a visual smoke-test - dark/light toggle, open each gen editor, Pokedex Color clamp, a Gen7 shiny trainer round-trip, and randomize-with-an-editor-open (editor should close, no data loss) - then patch a ROM and confirm drain/recoil moves + trainer IVs in-game.

## 2026-06-16 - Feature: Gen3/4 Move Tutor editor tab

Hypothesis: Gen3/4 games lack move tutors. Finding: FALSE (UI gap, not an engine gap). Emerald + FRLG (Gen3) and Platinum + HG/SS (Gen4) have move tutors and Gen3/4RomHandler fully support get/setMoveTutorMoves + get/setMoveTutorCompatibility; only the editor had no tutor tab (Gen5/6/7 did).
Evidence: Gen3RomHandler.hasMoveTutors() true for Em/FRLG (2652); Gen4RomHandler.hasMoveTutors() = romType != DP (4036); both getMoveTutorCompatibility() use boolean[moveCount+1] with flags[j*8+1] (Gen3 2761/2763, Gen4 2118/4121) — identical layout to what Gen5MoveTutorsSheetPanel expects (reads tutor i at flags[i+1]).
Change: the existing Gen5MoveTutorsSheetPanel is generation-agnostic (drives everything via the RomHandler interface), so it was reused in Gen3EditorFrame and Gen4EditorFrame — gated on romHandler.hasMoveTutors() so Ruby/Sapphire and Diamond/Pearl don't get an empty tab. Added field + tab + saveAll() call + onWindowClosing() restore in both frames (null-guarded).
Verify: clean Javac2 build (rm -rf out, 281 files) BUILD SUCCESSFUL; javap shows no duplicate woven methods on Gen3/4 EditorFrame; class-load probe loads both frames + the tutor panel with no ClassFormatError; jar re-overlaid.
Note (cosmetic): Gen5MoveTutorsSheetPanel is mis-named — it is actually a shared/generic panel (like MovesSheetPanel which Gen3/4 already reuse). A future rename to MoveTutorsSheetPanel would be tidier but is not required.
Next Step: user visual test on an Emerald/FRLG and a Platinum/HGSS ROM (open editor -> Move Tutors tab present + editable; on RS/DP the tab is absent), then a tutor compatibility round-trip in-game.

## 2026-06-16 - setMoveTutorCompatibility partial-byte mask bug (verifier audit)

Hypothesis: setMoveTutorCompatibility (Gen4RomHandler.java:4157-4161) preserves the wrong bits in the
final partial compat byte, making the last tutor moves un-clearable in HGSS.

Finding: Partially true / Mixed - bug is REAL but misattributed.
Evidence:
- gen4_offsets.ini: [Platinum (U)] (line 142+) defines MoveTutorCount=38, MoveTutorCompatBytesCount=5
  (lines 181/184). [HeartGold (U)] (line 264+) defines MoveTutorCount=52, MoveTutorCompatBytesCount=8
  (lines 299/302). HGSS reads from File<MoveTutorCompat>=waza_oshie.bin.
- AbstractDSRomHandler.java:255-268: getByteFromFlags/readByteIntoFlags map flag i -> bit i (tutors = LOW bits).
- Math: partial-byte branch uses S = 8 - amount + j*8.
  Correct shift = amount - j*8.
  Platinum (amount=38, j=4): correct=6, code=2 -> WRONG (tutors 35-38 OR'd sticky; the reviewer's mechanism).
  HGSS (amount=52, j=6): correct=4, code=8-52+48=4 -> ACCIDENTALLY CORRECT (no bug).
- hasMoveTutors() (4035) false only for DP; among DPPt only Platinum hits the buggy branch.

Reason: The buggy formula and line numbers are accurately identified, but the reviewer named the wrong
ROM type. HGSS (their stated target/impact) is not affected; Platinum (and DPPt -> only Plat has tutors) is.

Next Step: Fix formula to keep = (orig >>> (amount - j*8)) << (amount - j*8); verify on Platinum.

## 2026-06-17 - Audit #4 (deep bug + missing-feature pass) + fixes + Gen1/2 editor

Hypothesis: a 4th exhaustive multi-agent pass (15 finders -> adversarial verify -> completeness critic -> 2nd wave) surfaces more real bugs + missing features across every ROM handler (Gen1-7) and editor panel.
Finding: True. 119 issues confirmed: 87 bugs (5 high / 27 medium / 55 low), 18 missing features, 14 usability. (First run was wiped by a transient server rate-limit storm; re-run gently batched.)

FIXED (34 via per-file fixer fan-out + 4 by hand; all compile, no dup-woven methods, all classes load):
- HIGH: Gen1 TM shop-price offset inverted `(tm01 - i)` -> now `(i - tm01)/2` (was corrupting ROM on price randomize); Gen6/7 learnsets save now normalizes (drops placeholder move=0 rows); AbstractRomHandler.saveRom() now RE-THROWS RomIOException instead of swallowing (was masking failed saves as success); Personal sheet held-item edits for guaranteed-item species (clears guaranteedHeldItem on edit so common/rare take effect).
- MEDIUM (sample): Gen2 saveMoves now writes the +6 secondary-effect chance byte + loadMoves populates it; Gen2/Gen3 unmasked signed type-byte reads (& 0xFF); Gen5 hidden-item wrong skip-table bound; Gen5 originalDoubleTrainers dup accumulation; Gen7 getTMMoves `offset>0`; Gen7 setStarterHeldItems null-check; Gen5/7 getShopPrices no longer writes inside a getter; Gen5/6 forme egg-move save; Gen4 forme TM/HM read/write same file; trainer held-item picked by id not name; CSV parser handles quoted embedded newlines; coerceValue reports bad ints; Gen6/7 4th held-item column removed; Gen6 personal change-log records all fields; level-curve kahuna grand-trial vs E4 no longer collapse; Gen6 saveMoves writes absorb byte 19; Gen3 trainer class-byte mapping; PokemonCardView reset-to-default family revert + hatch-cycles relabel; MoveCard secondary% 0-255 + Gen6/7 recoil/absorb shared-byte note.
- BY HAND: Gen3 Move-card Effect Details section made read-only (those fields are derived from Effect Index and not written back by Gen3 saveMoves); Type-effectiveness edits now logged to ManualEditRegistry; closeOpenEditorFrames() made EDT-safe (batch randomize called it off-EDT); corrected the finishRandomization registry-clear comment (reinit reloads the ORIGINAL input ROM, not the randomized output).

DEFERRED (documented, not changed): Species.evolutionsFrom live-mutation / copyBaseFormeEvolutions aliasing / Evolution.equals-ignores-forme (#4/#45/#50 - these are the editor's live-mutation design, risky to change); 3-arg applyCsvDataToTable null-parent skip-warning on the non-Personal sheets; Gen7 MoveTutors 'ORAS' label (needs a Gen6 protected hook); the ~48 lower-confidence/low-severity latent items (signed-byte/bounds guards, cosmetic mislabels). The 18 missing-feature editors (wild-encounter/static/starter/trade/field-item editors, TM & tutor MOVE assignment, Gen4 trainer forme/ability) remain to propose.

FEATURE: full Gen 1 & Gen 2 editors (Gen1EditorFrame + Gen2EditorFrame), mirroring Gen3EditorFrame and reusing the interface-driven shared panels; tabs gated by capability (Gen1: no egg moves/tutors; Gen2: egg moves yes, tutors no). New openGen1Editor/openGen2Editor + two editor buttons (hardcoded text, enabled by generation) in RandomizerGUI. Gen 1's single Special stat is now a proper editable "Special" row in the Card View (get/setSpecial), line-scaling disabled for it; remaining Gen1-only inert controls (abilities/held-item/Sp.Atk-Def in the Personal Sheet) are cosmetic no-ops, not crashes.

Build: clean Javac2 (rm -rf out) 283 files BUILD SUCCESSFUL; javap no duplicate woven methods; 17-class load probe (incl. Gen1/2 frames + all changed handlers + FlatLaf) loads with no ClassFormatError; jar re-overlaid (79.46 MB).
Next Step: user runtime smoke-test - load RBY + GSC (editor buttons enable, open editor, Save All, Gen1 Special editable in Card View), then an Emerald/FRLG + Platinum/HGSS Move Tutors tab, then patch a ROM and confirm the high-severity fixes in-game (Gen1 shop prices, Gen6/7 learnsets, held items). Decide which of the 18 missing-feature editors to build next.

## 2026-06-19 - Add Wild-Encounter editor tab to all gen frames

Hypothesis: A generation-agnostic encounter editor can be built purely on the RomHandler interface
(getEncounters/setEncounters) and wired into Gen1-7 frames like the optional moveTutorsSheetPanel,
without touching any per-gen behavior.

Finding: True.

Evidence: New EncountersEditorPanel.java compiled clean; all 7 gen frames build (BUILD SUCCESSFUL,
284 src files). EncounterArea extends ArrayList<Encounter>; Encounter.maxLevel==0 means single-level
(confirmed in Encounter.toString). getSpeciesInclFormes() can repeat dex numbers across formes, so the
species combo resolves selections by the full "id: name+suffix" option string (unique) rather than by
leading number.

Reason: The editor operates only on DEEP COPIES of the area/encounter list and pushes via
setEncounters(true, working) solely in save() with the same useTimeOfDay flag used to read — so no
area/slot is ever dropped, reordered, or nulled, and close-without-save leaks nothing (handler untouched).
Levels clamp 1..100; maxLevel allows 0 (single-level) else floored at min. Edits log to
ManualEditRegistry "Wild Encounters". Tab gated by !getEncounters(true).isEmpty() (wrapped in try/catch),
null-guarded in saveAll/windowClosing like moveTutorsSheetPanel.

What Changed: Added src/.../editors/EncountersEditorPanel.java; added field + gated tab + onWindowClosing
+ save() wiring to Gen1-7 EditorFrame. No Gen3-7 logic changed beyond the one new tab.

Next Step: Runtime-test against a loaded ROM per gen (esp. Gen2 time-of-day fishing areas and Gen7 SOS
slots) to confirm round-trip writes and that gated frames with no encounter data omit the tab.

## 2026-06-19 - Trainer Level-Curve: evil-team bosses (#16) + Giovanni non-gym fights (#17)

Hypothesis: Evil-team leaders/admins are tagged `THEMED:<TEAM>-LEADER`/`STRONG` and Giovanni's non-gym
fights `GIO1`/`GIO2`(-LEADER); both are dropped by TrainerLevelCurveDialog.buildChain and need to become
their own milestones without breaking gym/E4/champion detection, monotonic ordering, or colliding with the
Viridian gym (GYM8 = Giovanni).

Finding: Partially true (the team-tag NAMING differs from the prompt) — features implemented.
- The boss/admin tags key on the CHARACTER name, not the team: `THEMED:MAXIE-LEADER`, `THEMED:ARCHIE-LEADER`,
  `THEMED:CYRUS-LEADER`, `THEMED:LYSANDRE-LEADER`, `THEMED:GUZMA-LEADER`, `THEMED:LUSAMINE-LEADER` (leaders);
  `THEMED:COURTNEY/TABITHA/MATT/SHELLY/MARS/JUPITER/SATURN/ZINZOLIN/COLRESS/ARIANA/PETREL/PROTON-STRONG`
  (admins) plus non-team mini-bosses `WALLY/GLADION-STRONG` and Gen7 trial captains. There is NO
  `TEAMROCKET-LEADER`/`TEAMAQUA-LEADER` tag anywhere — the prompt's assumed convention is wrong; the real,
  reliable signal is the `-LEADER`/`-STRONG` suffix (== Trainer.isBoss()/isImportant()).
- Non-suffixed `THEMED:<X>` are plain type-theme groups (SPROUTTOWER, JESSIE&JAMES, SHADOW1/2/3, and Gen2's
  suffixless ARIANA/PETREL/PROTON) — NOT bosses; correctly excluded by requiring the suffix. Consequence:
  Gen2 GSC gets no team milestones (its admins are untagged as STRONG), matching the game's own tagging.
- Giovanni: RBY/Yellow `GIO1`/`GIO2` (Gen1Constants tbc 29/0, 29/1) and FRLG `GIO1-LEADER`/`GIO2-LEADER`
  (Gen3Constants 0x15C/0x15D) are SEPARATE Trainer objects from `GYM8-LEADER` (29/2 resp. 0x15E) — verified
  via Gen1Constants.getTrainer (n-th distinct object per trainerclass) and distinct Trainer.index. So no
  object overlap, no double-shift, no gym8 overwrite.

Evidence: TrainerLevelCurveDialog.java (full); Gen1Constants tagTrainersUniversal L243-263 + getTrainer
L494-505; Gen3Constants trainerTagsFRLG L809-811 (GIO) + RSE/Em THEMED L688-697/773-778; Gen4/5/6/7 THEMED
tables; Trainer.java tag javadoc L37-92 + isBoss L197-200 / isImportant L202-204; TrainerPokemonRandomizer
getTrainerGroups L429-457 (GIO folds into GYM8 for type-theming; "-" split).

What Changed (all private, EDT-only, in TrainerLevelCurveDialog.java):
1. New patterns: `THEMED_BOSS = THEMED:(.+?)-(LEADER|STRONG)`, `GIOVANNI = GIO(\d+)(?:-LEADER)?`.
2. buildChain loop: two new else-if branches (placed after UBER, before ELITE) collect Giovanni fights into
   `Map<Integer,Milestone> giovanni` (keyed by GIO number) and team bosses into
   `Map<String,Milestone> teamBosses` (keyed by character name, folding rematches). Labels: "Giovanni
   (Hideout)"/"Giovanni (Silph Co.)"; "Maxie (Team Leader)" / "Courtney (Admin)" via titleCase + suffix.
3. Milestone gained `appendRepName` (false for these, set via new newBossMilestone) so the descriptive label
   isn't suffixed with a redundant rep name.
4. Refactored the per-milestone ace/label finalize into finalizeMilestone(); new insertByAce() splices each
   extra milestone before the first chain entry with a strictly-higher ace (rep = lowest-index = first
   encounter sets currentAce).
Per-version gating is AUTOMATIC (tags are scanned, not assumed) — RBY shows GIO1/GIO2, FRLG shows the
-LEADER variants, RSE/Em/Gen4-7 show their team bosses, none appear where the tags are absent.

Ordering/collision safety: gym/E4(incl. FRLG ELITEn-r)/champion/rival-as-champion/B2W2/Gen7 paths are
untouched (new branches only catch GIO* and THEMED:*-LEADER/STRONG, which those paths never matched).
buildCurvePoints' running-max + tied-merge (the remap's monotonicity guarantee) is unchanged and keyed on
ace, independent of chain display order. insertByAce inserts at the EARLIEST valid slot so admin/boss fights
stay in the first-8-gyms section of dual-region chains (never past the league into lower-ace Kanto gyms) and
never displace the lowest-ace gym from index 0 (Scale/Even-ramp anchor stays the first gym). GIO vs gym8 are
distinct Trainer objects → distinct milestones/spinners; if a GIO and a gym share an ace, only the curve
CONTROL point merges (max target kept), the UI milestones stay separate.

Build: rm -rf out + Javac2, 290 files, BUILD SUCCESSFUL.

Next Step: Runtime spot-check — RBY: Giovanni (Hideout)/(Silph Co.) appear between the right gyms and Gym 8
(Viridian) stays its own row; FRLG: same with -LEADER tags; Emerald: Maxie/Archie (Team Leader) +
Courtney/Tabitha (Admin) slot between gyms by level; HGSS (dual-region): Rocket admins (ARIANA/PETREL/
PROTON-STRONG) land among Johto gyms, not after the league. Watch Gen7 for clutter (many -STRONG trial
captains, some USUM-RR postgame e.g. SOLIERA/DULSE-STRONG whose first index may already be high-level) —
acceptable but a future option could restrict to `-LEADER` only if too noisy.

## 2026-06-17 - Built all 17 missing features (encounters/statics/starters/trades/items + editable TM/tutor/trainer/learnset/type/curve)

Hypothesis: the 17 confirmed missing-feature/usability gaps from Audit #4 can each be implemented robustly (per-feature build + dup-check + load-probe + bug review), in order.
Finding: True - all 17 implemented; each compiles, no duplicate woven methods, all classes load; final 25-class load probe clean; jar overlaid (79.58 MB).
Each was built to avoid the audit's recurring defect classes (round-trip read<->write, range clamps, null/bounds, EDT, manual-edit logging, no silent no-op edits).

New editor panels (generic, interface-driven; gated per ROM capability; wired into all 7 gen frames):
- #1 EncountersEditorPanel (wild encounters: areas->slots, species/min/max level; deep-copy round-trip via setEncounters(true,...)).
- #2 StaticEncountersEditorPanel (legendaries/gifts; gated on canChangeStaticPokemon(); held item by id; copy-ctor deep copy).
- #3 StartersEditorPanel (starters + held items; per-gen held-item list sizing; setStarters/setStarterHeldItems).
- #4 InGameTradesEditorPanel (given/requested(+Any=null)/nickname/OT/heldItem; nickname<=10 / OT<=7 DocumentFilter; ivs read-only).
- #5 FieldItemsEditorPanel (TM-ness-per-index invariant enforced by construction + guard; pools mirror ItemRandomizer).
- #6 PickupItemsEditorPanel (verified: item IS written, probabilities are NOT -> item editable, probs read-only with column-sum guide).
- #7 ItemsEditorPanel (verified: item name/id final + no ROM write-back -> id/name/TM read-only; Allowed/Bad flags editable, affect randomizer pools, applied on next randomize).

Make-existing-editable + fixes:
- #8 TM move assignment + #9 tutor move assignment: "Edit TM/Tutor Moves..." dialog (shared helper in EditorUtils) -> setTMMoves/setMoveTutorMoves with exact-size lists; refreshes compat headers; BW1 no-op / BW2 size-match handled.
- #10 Gen4 TrainerPokemon forme exposed (gen>=4; was gen>=5), ability slot kept gen>=5; forme<<2 bit-pack verified.
- #11 Trainer names + class names editable (gated on canChangeTrainerText(); name slot = trainerIndex-1 for Gen4-7, Trainer.name sync for Gen3, Gen5 PWT tail preserved; len caps 12).
- #12 Learnset Add/Remove Move buttons implemented (operate on selected species); #13 dynamic move-slot cap = max(20, largest learnset + 4) so >20-move learnsets no longer truncate (save path uses full list - truncation structurally impossible).
- #14 Per-forme egg moves: Gen5/6 store egg moves per BASE species only (forme editing impossible by format) -> Gen5/6 forme rows honest read-only; added supportsFormeEggMoves() (Gen7 true) and unlocked Gen7 per-forme editing.
- #15 Type-editor ZERO immunities: added hasTypeImmunityEditSupport() capability (mirrors hasTypeEffectivenessSupport; ZERO round-trips Gen1-6) and gated the ZERO option on it; Gen1/2 overflow already caught.
- #16 evil-team bosses (THEMED:<name>-LEADER/-STRONG) + #17 Giovanni (GIO1/2, FRLG GIO*-LEADER) added as first-class level-curve milestones; per-version auto-gated by tag scan; monotonic ordering preserved; Giovanni kept distinct from his Viridian gym.

Build cadence: every feature got an independent clean Javac2 build (rm -rf out), javap dup-check, and a class-load probe before the next. Final: 290 source files, BUILD SUCCESSFUL, 25-class probe clean, jar 79.58 MB.
Deferred/notes for runtime: per-gen string-length caps (trade nickname/OT, trainer name/class) are conservative guesses; Gen7 -STRONG curve rows may be noisy (one-line regex tweak if undesired); "None" held item on Gen2/6/7 no-ops (NPE caught). All need an in-game ROM round-trip test.
Next Step: user loads ROMs per family and round-trips a save for each new editor; report any per-gen quirks for tuning.

## 2026-06-17 - Tackled the deferred Audit #4 items (low/latent bugs + design/inherent calls)

Hypothesis: the 46 remaining unfixed Audit #4 bugs (the ones excluded from the first fan-out: low-confidence latent issues + engine-design + .ini-inherent) can be triaged and the safe ones fixed, with the rest given a reasoned decision.
Finding: True. ~32 fixed; ~14 deliberately deferred with rationale. Final clean build 290 files BUILD SUCCESSFUL, no dup-woven methods, 19-class load probe clean, jar overlaid (79.58 MB).

FIXED (defensive guards / masks / clamps / consistency, applied via 4 parallel edit-only agents + my own #65; each only affects the invalid/out-of-range path or is byte-faithful):
- Gen1: StaticPokemon.setLevel bounds guard; map-name read honors stored bank byte; readTrainer/readTypeTable/readHiddenItems terminator-overrun guards.
- Gen2: getLevel & 0xFF (signed); writePalette 2-color guard; saveBasicPokeStats stat/catch-rate 0-255 clamp.
- AbstractGB/GBC: writeWord bit-mask (not div/mod); lengthOfData/StringAt bounds guards; translateString safe on malformed backslash escape.
- Gen3: Mossdeep Steven IVs scaled 0-31 like all other Gen3 trainers (+ signed read fix); move type read & 0xFF; field-item/shop/pickup reads null/bounds-guarded.
- Gen4: Pokedex color(0-6)+flip(bit7) packed into the correct single byte (was clobbering bit7 / writing unused byte); saveBasicPokeStats range clamps; DPPt egg-move writeback bounded to the original table extent (no overlay overrun).
- Gen5: applyCorrectStaticMusic ARM9 scan bounds + not-found guard; saveMoves accuracy clamp 101->255 (matches read range); encounter species-id bounds/null; setMoveTutorMoves BW2 size-mismatch now warns.
- Gen7: getMoveTutorCompatibility returns empty for SM (no tutors), mirroring the setter.
- AbstractDS: getROMCode/getVersion from file now try-with-resources (no file-lock leak) + exact-read instead of ignored skip().
- Editors: MoveCard Target max gen4=65535, Accuracy max gen3/4=100 / gen5+=101 (display matches stored); gender-ratio display clamped 1-99% for non-sentinel ratios; CardView egg-move add dedup + gen-aware cap; MovesSheet priority/effect/effect-chance bounded (priority no longer byte-wraps); TrainerEditor save() now sets has-items/has-custom-moves flags two-way (off when emptied); Gen5EggMoves getValueAt null-safe; EditorUtils escapeCSV quotes CR/edge-whitespace + ID-match falls back to numeric col0 when header isn't literally "ID".
- TrainerLevelCurve: Gen7 eliteLabel no longer mislabels island/E4 slots as "Grand Trial"; shiftTeam preserves a team's internal level spread instead of flattening at the clamp.
- #65 (by me): EditorDataCache.clear() added + called from closeOpenEditorFrames() so a replaced ROM's handler+maps are released (no leak/staleness).

DEFERRED (reasoned, not blindly changed):
- DESIGN (engine ripple risk): #4 editors mutate live Species.evolutionsFrom -> becomes randomizer baseline (this is the editor's intended live-mutation model); #45 copyBaseFormeEvolutions list-aliasing and #50 Evolution.equals/hashCode ignoring forme (changing equals/hashCode ripples through engine Sets/Maps - needs the test suite, out of scope here); #55 re-saving to original value leaves a stale log line (cosmetic; dedup already added).
- GUI policy: #18 disposing editors at randomize-start discards unsaved sheet edits while live card edits apply - a deliberate policy (Save All in the editor first); left as-is, documented.
- INHERENT (ROM layout): #43/#49 fossil (Gen1) and roaming (Gen2) statics share one level byte, so setStaticPokemon aliases them by design - not a code bug.
- CROSS-FILE skips (need a coordinated multi-file change): EditorUtils 3-arg applyCsvDataToTable null-parent (callers must pass a parent to surface skip warnings on the Personal/Moves/TMs sheets); Gen7 MoveTutors "ORAS" label (needs a protected hook in Gen6MoveTutorsSheetPanel).

NEEDS IN-GAME TEST (behavior-changing, not pure guards): Gen4 Pokedex color/flip byte layout; Gen3 Mossdeep Steven IV scaling; Gen5 move accuracy write range; Gen1 map-name bank read; Gen4 DPPt egg-move extent bound. Each matched a verified audit finding but alters ROM read/write, so confirm a vanilla round-trip per affected gen.
Next Step: user round-trips the behavior-changing fixes on real ROMs; decide whether to take on the 4 cross-file/design items as a dedicated follow-up.

## 2026-06-17 - Verified + fixed ChatGPT's POTENTIAL_BUGS.md (5 claims, all real)

Hypothesis: the 5 bugs in POTENTIAL_BUGS.md (external review) are real and fixable.
Finding: True - all 5 verified against the code and fixed. Final clean build 290 files BUILD SUCCESSFUL, no dup-woven methods, 10-class load probe clean, Roms.java (test) compiles, jar overlaid (79.58 MB).

1. (High) Gen1 trainer-name editing wrote to the WRONG trainer. TRUE: Gen1 CanChangeTrainerText=1 (RB/Y) so the name field showed, but Gen1.getTrainerNames() is a SINGULAR trainer-CLASS list (filtered by Gen1Constants.singularTrainers), not one-per-instance; TrainerEditorPanel mapped nameSlot = trainerIndex-1 onto it (the bounds-check at 204 only prevented a crash, not the mis-map). FIX: added `perTrainerNames = canChangeText && generation != 1`; gated the per-trainer Name field, the t.name pre-sync, and the setTrainerNames push on it. Class-name editing (getTrainerClassNames/setTrainerClassNames, the correct Gen1 surface) still works for all gens.
2. (High) Editor save() swallowed setter failures and still showed success. TRUE: e.g. EncountersEditorPanel.save() caught the setEncounters exception, printStackTrace, then showed "Save Complete". FIX: 6 new panels (Encounters/Static/Starters/Trades/FieldItems/Pickup) + TrainerEditorPanel now track an `ok`/`saved` flag, show an unguarded "Save Failed" ERROR dialog on exception (and on a `false` return for setStaticPokemon/setStarters, and on a starter held-item failure), and only log + show success when the write succeeded. TrainerEditorPanel additionally skips its backup re-baseline on failure so edits remain revertible.
3. (Medium) Roms.java getRoms() removeIf mutated the shared static ALL_ROMS_BY_GENERATION list. TRUE (test-only). FIX: `new ArrayList<>(ALL_ROMS_BY_GENERATION.get(gen-1))` before removeIf. (Verified compiles; test/ is outside the main Javac2 build.)
4. (Medium) NCCH.updateFileMetadataTable sort cast (int)(long-long) can overflow. TRUE. FIX: Long.compare(f1.fileDataOffset, f2.fileDataOffset).
5. (Medium) NARCArchive.readNitroFrames had no bounds validation (checked-in error_2025-11-01 log shows an AIOOBE from this path). TRUE. FIX: validate `offset+8 <= data.length`, `frame_size >= 8`, `offset+frame_size <= data.length` (long-safe) before reading each frame; throw IOException with archive context otherwise.

Assessment: ChatGPT's review was accurate - all 5 were genuine (2 of them were in my newly-added editor code: #1 from the trainer-names feature, #2 from the new editor panels). 
Next Step: in-game test of Gen1 trainer editing (Name field now hidden; class names editable) and a forced save-failure to confirm the ERROR dialog; the NCCH/NARC fixes need a large Gen6/7 ROM rebuild + a malformed-NARC open respectively.

## 2026-06-19 - POTENTIAL_BUGS items #26/#27/#28/#31/#32/#40/#41 + #12 (logger/GameRandomizer sides)

Hypothesis: Items #26,#27,#28,#31,#32,#40,#41 and the GameRandomizer/logger sides of #12 are genuine and fixable within the allowed file set (GameRandomizer, RandomizationLogger, CliRandomizer, TMHMTutorCompatibilityRandomizer, SpeciesMovesetRandomizer, Gen3to5PaletteRandomizer, CheckValueCalculator, Species).

Finding: Mostly TRUE (6 fixed, 1 deferred). Per item:
- #26 TRUE/FIXED — Species.getRandomCosmeticFormeNumber: `num==cosmeticForms` was unreachable (nextInt(cosmeticForms) is 0..n-1) and realCosmeticFormNumbers never includes the base forme, so base was never selectable for those species; bounds check was `> size` (should be `>=`). Fixed: non-empty path picks from size+1 (top value = base); empty path unchanged (base already reachable via num==0 — did NOT double-count). All 4 callers just setForme(result), base forme number is valid for all.
- #27 TRUE/FIXED — fullTMHMCompatibility() never set tmhmChangesMade; FULL TM/HM compat silently omitted from overview+log and skipped copyTMCompatibilityToCosmeticFormes. Added `tmhmChangesMade = true`.
- #28 TRUE/FIXED — maybeFixTrainerZCrystals() runs BEFORE metronome-only is applied, so Gen7 trainers keep Z-Crystals for pre-reset moves; metronomeOnlyMode() also never set changesMade. Reorder was risky (metronome must run after trainers), so added a SECOND randomUsableZCrystals() call right after metronomeOnlyMode() in applyRandomizers() (no-op outside Gen7, repairs from reset movesets), and set changesMade=true in metronomeOnlyMode().
- #31 TRUE/DEFERRED — CheckValueCalculator omits trainer moves/items/formes, static levels/formes/items, totem extras, TM/HM+tutor compat, trades, shop/field/pickup items, palettes, move data. Confirmed broad. Deferred per instruction: any change shifts the check value for everyone and breaks comparison vs older builds; no clean "obviously safe" subset. Left unedited; needs a deliberate decision (expand fully + version-bump the value, or narrow the Bundle.properties race-mode copy).
- #32 TRUE/FIXED — CliRandomizer discarded GameRandomizer.randomize() Results and always printed "Randomized successfully!" + wrote .log. Now captures Results: on !wasSaveSuccessful prints error + stacktrace and returns false (CLI exit 1); only writes log + prints success after confirmed save; !wasLogSuccessful is a warning (ROM is valid).
- #40 TRUE/FIXED — Gen3to5PaletteRandomizer.getPalettePartDescriptions: `validIndex = paletteIndex <= size()` then `.get(paletteIndex)` → IOOBE when number-1 == size(); also no `>=0` guard. Fixed to `paletteIndex >= 0 && paletteIndex < size()`, keeping PaletteDescription.BLANK fallback.
- #41 TRUE/FIXED — GameRandomizer.randomize discarded saveRom() boolean; AbstractGBRomHandler.saveRomFile returns false (not throws) on a non-"Access is denied" IOException → silent success on a bad write. Now captures the boolean and throws RomIOException("Failed to save ROM to "+filename) on false → stored in results.e → GUI/CLI report failure. Verified AbstractRomHandler.saveRom re-throws RomIOException and false is the ONLY non-throw failure path (saveRomDirectory GB returns true), so no legitimate false-as-success path is suppressed.
- #12 (logger/GameRandomizer sides) PARTIAL/FIXED-WHAT-WAS-IN-SCOPE — Custom-starters fast path returns before changesMade=true → fully-custom starters omitted from log/overview. Fixed in RandomizationLogger: shouldLogStarters() also true when StartersMod==CUSTOM (custom always writes), and overview line now uses shouldLogStarters(). Static level-only path (onlyChangeStaticLevels) writes but never sets changesMade → fixed logging side: shouldLogStaticPokemon() also true when StaticPokemonMod==UNCHANGED && isStaticLevelModified(); overview line updated to match.

Evidence: Read all 8 target files + RomIOException, Randomizer base, StarterRandomizer, StaticPokemonRandomizer, RomHandler iface (saveRom/setStarters/setStaticPokemon all boolean), TrainerPokemonRandomizer.randomUsableZCrystals (Gen7-only, safe unconditional), AbstractRomHandler.saveRom, AbstractGBRomHandler.saveRomFile, RandomizerGUI lines 1255-1273 (branches on wasSaveSuccessful/getException), all getRandomCosmeticFormeNumber callers.

Reason: Each fixed item was verified against real code with a concrete failure path. #31 deferred because the instruction mandated low-risk and the fix is inherently a compatibility-breaking value change with no safe partial.

What Changed: 6 items fixed, 1 deferred (#31). EDIT-ONLY, no build run (per instruction). Cross-file deferrals: converting setStarters/setStaticPokemon false-returns into RandomizationExceptions, and #25's onlyChangeStaticLevels forme re-roll + its changesMade=true, require editing StarterRandomizer/StaticPokemonRandomizer which were explicitly out of scope.

Next Step: Compile (IntelliJ Javac2 Ant per project memory) and runtime-check: (a) Gen7 metronome-only → trainer Z-Crystals become Normalium Z; (b) CLI on a read-only output dir → exit 1 + "Randomization failed"; (c) Gen3-5 palette randomize on a data set with a species number == paletteDescriptions.size()+1; (d) fully-custom starters → starters appear in log overview. Decide #31 direction separately.

## 2026-06-19 - Randomizer engine bug fixes (POTENTIAL_BUGS #10-14, #22, #24, #25, #29, #30)

Hypothesis: ChatGPT's POTENTIAL_BUGS items #10,#11,#12,#13,#14,#22,#24,#25,#29,#30 (starter/static/wild/trainer-name/item/trainer-pokemon randomizers + SpeciesSet) are real bugs fixable within those 7 files.

Finding: All 10 confirmed True and FIXED (none refuted, none deferred).

Evidence + What Changed (file:line as of edit):
- #10 StarterRandomizer.chooseTypeForStarters: `availableByType.get(type).size()` NPE'd on absent type buckets (sortByType(false) returns null per its own javadoc) and used `> numStartersNeeded` (rejected exact fits). Fixed to null-guard + `>= numStartersNeeded`. Also guarded the explicit single-type path (user-chosen singleType) so a null/too-small pool throws RandomizationException instead of NPE in chooseStartersBasic.
- #11 SpeciesSet.getRandomSpecies: cache-reset condition was inverted (`size/cacheSize > 0.5` rebuilt while cache fresh, never rebuilt once drained <50% → retry-loop spin). Flipped to `<`. Selection correctness unchanged (retry loop guarantees validity); this is a perf fix matching the "How much of the cache must consist of removed Species before resetting" comment + CACHE_RESET_FACTOR intent.
- #12 setStarters/setStaticPokemon return boolean and several handlers return false. Now both StarterRandomizer paths (custom fast-path + main) and StaticPokemonRandomizer.randomizeStaticPokemon throw RandomizationException on false; custom-starter fast-path now sets changesMade=true (was returning without it).
- #13 TrainerPokemonRandomizer Elite-Four/rival path did `getStarters().subList(0,3)` — crashes Yellow (2 starters). Now `subList(0, Math.min(3, allStarters.size()))`. (GUI restoreStateFromSettings + Settings.tweakForRom + Gen1RomHandler parts are in OTHER files = OUT OF SCOPE, reported not fixed.)
- #14 TrainerNameRandomizer.randomizeTrainerClassNames had `while (changeTo.length() > maxLength) changeTo = pickFrom.get(...)` — infinite loop if no candidate fits; also used String.length() vs internalStringLength() used for bucketing. Replaced with up-front filter on internalStringLength()<=maxLength; keeps original name if none fit. (randomizeTrainerNames already had a 100-try inner cap + 10000 outer cap — left as-is.)
- #22 ItemRandomizer.randomizeShopItems could throw NoSuchElementException/EmptyStackException/IllegalStateException late when guaranteed items don't fit. Added validateShopRandomization(): throws RandomizationException if guaranteed.size() > mainGameSlots (all guaranteed go to main-game shops) or if non-guaranteed slots needed but candidate pool empty. Proven sufficient: guaranteed<=mainGameSlots ⇒ shopItemCount>=nonMainGameSlots ⇒ non-main-game do/while never exhausts ⇒ newItems empties.
- #24 StarterRandomizer.chooseUniqueTypeStarters called available.getRandomSpecies without empty-check → IllegalStateException after the initial numeric sanity check when not enough distinct type groups. Added isEmpty() guard → RandomizationException.
- #25 StaticPokemonRandomizer.onlyChangeStaticLevels called setSpeciesAndFormeForStaticAndLinkedEncounters → re-rolled cosmetic formes, collapsed alt-formes to base, overwrote linked encounters. Rewrote to change only level/maxLevel (added maxLevel, was missing) + set changesMade=true + return-check.
- #29 WildEncounterRandomizer.randomizeEncounters level-only path returned before changesMade=true. Added the flag.
- #30 WildEncounterRandomizer.enforceMultipleSpecies used rSpecService.randomSpecies (ignored noLegendaries/area-bans/type/evo restrictions; infinite loop if only 1 base species). Rewrote to pick from setupAllowedForReplacement(present,area,zoneType) minus the present species (single-shot, not a loop); leaves area unchanged if no legal alternative. Encounter.equals() includes species so one swap breaks the distinct==1 condition.

Reason: Each verified against actual code; all matched ChatGPT's evidence. Changes are minimal, respect Java 8 + surrounding style, and only #14's RNG-call sequence changes randomization output (unavoidable when removing the infinite loop).

Next Step: Single combined build (Javac2 Ant per project memory) + runtime spot-checks: single-type starters with restrictive filters (exact-fit pool now accepted, missing-type no longer NPEs); Yellow with rivalCarriesStarter + E4-unique (no subList crash); custom trainer class names all longer than a class's limit (no hang); shop randomize with many guaranteed items on a small-shop game (clean RandomizationException); Gen7 static level-only (formes/linked encounters now untouched). The out-of-scope #13 GUI/Settings/Gen1 pieces still need handling in their own files.

## 2026-06-17 - POTENTIAL_BUGS.md expanded to 43; verified+fixed items 6-43

Hypothesis: ChatGPT's expanded review (items 6-43, on top of the already-fixed 1-5) is mostly real and fixable.
Finding: True - verified each against the code via 6 parallel subsystem agents (verify-first; refute/defer if wrong or risky). ~34 fixed, ~5 deferred-with-reason. Final clean build 290 files BUILD SUCCESSFUL, no dup-woven methods, 29-class load probe clean, jar overlaid (79.59 MB). One compile error (duplicate `results` var in CliRandomizer #32) was caught by the build gate and fixed (renamed to randoResults).

FIXED (high-value highlights): #20 NCCH header offsets written as 1 byte -> now 4-byte LE (was corrupting 3DS output); #13 Yellow starter subList(0,3) crash -> Math.min + starterCount guards (engine) + GUI/Settings starter-restore guards; #32 CLI printed success on failed save -> checks Results, exit 1; #41 GameRandomizer ignored saveRom() false -> throws RomIOException; #33 failed ROM open left stale handler -> romHandler=null + disable gen editor buttons; #34 error-log left System.err redirected on null handler -> try/finally restore; #43 bad config.ini int crashed startup -> parse-with-default; #18 NDS overlay write off-by-one (<=) -> <; #21 NDS/NCCH stale trailing bytes -> setLength; #23 NARC filename loop didn't advance -> offset += fnLength + bounds; #15 AMX / #16 Mini bounds validation; #19 Gen6/7 setStrings now surfaces failures; #42 Gen6 intro find() validated; #10/#24 starter pool guards; #11 SpeciesSet cache reset comparison flipped; #12/#27/#28/#29 changesMade/log flags; #25 static level-only no longer re-rolls formes; #30 force-multiple wild respects restrictions; #14 trainer-class-name loop bounded; #22 shop validation; #26 cosmetic-forme base now selectable; #40 palette index off-by-one; #7 TM/tutor dialog save/revert lifecycle (snapshot guard); #8 trainer text limits from handler; #9 Gen5 debug logging default false; #35 moves-sheet wrapper repaint; #36 IPS detected as IPS not ZIP; #37 batch done() observes worker exceptions; #39 (partial) type-edit flag reset on reinitialize.

DEFERRED (verified real but risky/policy - documented, NOT changed): #6 dispose-without-prompt is a deliberate policy; #17 GARC FATB multi-subfile rewrite (no reachable caller creates multi-subfile dirs + no ROM fixture to verify the serialization - a wrong rewrite corrupts every rebuilt GARC); #31 race check-value omits many fields (expanding it breaks cross-build comparison - needs a versioning decision); #38 batch EDT/handler-ownership race (needs a batch-state refactor across a huge method); #39-remainder (truly un-baking a manual type edit needs re-reading the original ROM mid-loadQS).
NEEDS IN-GAME TEST (binary ROM-write behavior changed): #20 NCCH header writes (3DS save/boot), #21 NDS/NCCH truncation on overwrite, #23 NARC named round-trip, #18 overlay write, #25 static level-only, #26 cosmetic forme distribution.
Also noted by agents (out of scope, follow-up): NARC reader starts filename loop at offset 8 vs writer at 16 (named-NARC round-trip); residual setIntroPokemon write-failure swallow.

## 2026-06-19 - Feature: "Buff / gate base stats" (BaseStatisticsMod.GATED)

Hypothesis: A 4th base-stats mode could be added next to Unchanged/Shuffle/Random by appending GATED to the enum, writing a 5-byte gate block after the romName block (inside the CRC region) in Settings.toString(), and gating GATED's UNCHANGED bit for back-compat - WITHOUT bumping VERSION or breaking existing presets.
Finding: True.
Evidence: Round-trip test (23 assertions) PASSED - GATED + all 7 sub-fields survive toString/fromString; RANDOM/SHUFFLE/UNCHANGED unaffected; a simulated OLD (5-bytes-shorter, CRC-recomputed) preset still reads cleanly and keeps gate defaults. Clean Ant (javac2) BUILD SUCCESSFUL.
Reason: SettingsUpdater only runs when version<VERSION (old presets, which lack the block); the fromString read guard `gateIdx+5 <= data.length-8` makes shorter inputs fall back to defaults. restoreEnum maps bit positions to ordinals 0/1/2 only, so GATED (appended last) never collides; GATED also sets the UNCHANGED bit so old readers fall back gracefully.
Files: Settings.java (enum+5 gate enums+fields+getters/setters, byte-1 write, gate block write/read), SpeciesBaseStatRandomizer.java (gateSpeciesStats + PROPORTIONAL/EVEN/RANDOM distribution + PER_STAGE/WHOLE_LINE/KEEP_BELOW_EVO), Gen1SpeciesBaseStatRandomizer.java (applyNewBST override for the unified Special stat), GameRandomizer.java (case GATED), RandomizerGUI.java (4th radio + 7 sub-controls added programmatically, not via .form).
Next Step: GUI runtime test - confirm the radio/sub-controls render in the base-stats panel and enable/disable correctly; smoke-test an actual randomize run with GATED on a ROM.
