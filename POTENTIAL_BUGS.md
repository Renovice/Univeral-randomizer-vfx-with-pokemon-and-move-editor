# Potential Bugs Review

Date: 2026-06-19

Scope: manual deep review of the current working tree. No code was changed as part of this review.

## 1. Gen1 trainer-name editing can write names to the wrong trainers

Severity: High

Files:

- `src/com/dabomstew/pkrandom/pokemon/editors/TrainerEditorPanel.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen1RomHandler.java`
- `src/com/dabomstew/pkromio/constants/Gen1Constants.java`

Evidence:

- `TrainerEditorPanel` assumes a trainer's name slot is `trainer.getIndex() - 1`.
- `Gen1RomHandler.getTrainerNames()` returns only singular trainer-class names, not one name per trainer instance.
- `Gen1RomHandler.loadTrainers()` assigns trainer indexes sequentially across trainer instances.
- `Gen1Constants.singularTrainers` is a sparse set of trainer class IDs, not trainer instance IDs.

Impact:

Editing a Gen1 trainer name in the trainer editor can update the wrong name slot. The UI can appear to edit one trainer while the ROM write changes a different singular trainer/class name.

Suggested fix:

Do not map Gen1 trainer instance indexes directly onto `getTrainerNames()` slots. Either expose a handler-level name-slot mapping API, special-case Gen1 singular trainer class names, or hide per-trainer name editing when the handler cannot provide a correct one-to-one mapping.

## 2. Several editor save methods swallow write failures and still show success

Severity: High

Files:

- `src/com/dabomstew/pkrandom/pokemon/editors/FieldItemsEditorPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/EncountersEditorPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/StaticEncountersEditorPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/PickupItemsEditorPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/InGameTradesEditorPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/StartersEditorPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/TrainerEditorPanel.java`

Evidence:

- Several panels catch broad `Exception` around ROM setter calls, print the stack trace, and then continue to display a save-complete message.
- `TrainerEditorPanel.save()` catches failures from `setTrainers()` and later rebaselines its backup state, which can make revert/close behavior unreliable after a failed save.
- `StartersEditorPanel` warns on starter write failure, but held-item write failure can still be swallowed before a success message.

Impact:

Users can be told their edits were saved even when the ROM handler rejected or failed to write them. This can cause silent data loss and make debugging ROM-specific failures much harder.

Suggested fix:

Make editor save methods return success/failure or throw a domain-specific save exception. Do not show success or rebaseline editor state when any ROM setter fails. Save-all flows should aggregate failures and show which editor failed.

## 3. ROM test filtering mutates global ROM lists

Severity: Medium

File:

- `test/test/com/dabomstew/pkromio/romhandlers/Roms.java`

Evidence:

- `ALL_ROMS_BY_GENERATION` stores the static generation lists directly.
- `getRoms()` retrieves one of those lists and calls `removeIf()` on it when filtering by region.

Impact:

One parameterized test can permanently remove ROM entries from the shared static lists. Later tests in the same JVM can run against an incomplete ROM set, hiding coverage gaps or causing order-dependent failures.

Suggested fix:

Copy the list before filtering:

```java
List<String> ofGen = new ArrayList<>(ALL_ROMS_BY_GENERATION.get(gen - 1));
```

Then call `removeIf()` on the copy.

## 4. 3DS RomFS rebuild sort comparator can overflow

Severity: Medium

File:

- `src/com/dabomstew/pkromio/ctr/NCCH.java`

Evidence:

- File metadata is sorted with:

```java
fileMetadataList.sort((FileMetadata f1, FileMetadata f2) -> (int) (f1.fileDataOffset - f2.fileDataOffset));
```

- `fileDataOffset` is a `long`.

Impact:

For large Gen6/Gen7 RomFS images, two offsets can differ by more than `Integer.MAX_VALUE`. Casting the long difference to `int` can flip comparator ordering, causing rebuilt file offsets to be assigned in the wrong order.

Suggested fix:

Use a non-overflowing comparator:

```java
fileMetadataList.sort((f1, f2) -> Long.compare(f1.fileDataOffset, f2.fileDataOffset));
```

## 5. NARC frame parsing lacks bounds validation

Severity: Medium

Files:

- `src/com/dabomstew/pkromio/newnds/NARCArchive.java`
- `error_2025-11-01-06-34-10.txt`

Evidence:

- `NARCArchive.readNitroFrames()` reads frame headers and copies frame data without validating that the frame offset and frame size are within the archive byte array.
- The checked-in error log shows an `ArrayIndexOutOfBoundsException` from this path during ROM opening.

Impact:

Malformed, misdetected, or unsupported ROM data can produce unchecked crashes or confusing bug reports instead of a clean "unsupported/corrupt ROM" error.

Suggested fix:

Before reading a frame, validate:

- `offset >= 0`
- `offset + 8 <= data.length`
- `frame_size >= 8`
- `offset + frame_size <= data.length`

Throw a checked `IOException` or `RomIOException` with archive context when validation fails.

## 6. Closing editor frames before randomization handles unsaved edits inconsistently

Severity: Medium-High

Files:

- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/EditorDataCache.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/PersonalSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/MovesSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/TMsSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/LearnsetsSheetPanel.java`

Evidence:

- `RandomizerGUI.performRandomization()` calls `closeOpenEditorFrames()` before creating settings and running randomization.
- `closeOpenEditorFrames()` disposes editor windows directly, and the comment explicitly notes that `dispose()` does not fire `windowClosing`.
- Live-object sheets such as personal data and move data mutate `Species` / `Move` objects immediately, then rely on `onWindowClosing()` to restore backups when the user closes the editor normally.
- Cache-backed sheets such as learnsets, egg moves, TM/HM compatibility, and tutor compatibility mutate detached maps from `EditorDataCache`.
- `closeOpenEditorFrames()` clears `EditorDataCache` after disposing the windows.

Impact:

If the user edits data in an editor window and clicks Randomize without using the editor's Save / Save All first, some unsaved edits can be kept while others are discarded:

- Live-object edits can survive because `windowClosing()` restore is skipped.
- Cache-backed map edits can be lost because `EditorDataCache.clear()` drops the edited detached maps.
- Working-copy panels can also be discarded because their `save()` methods were never called.

This makes "unsaved editor changes" depend on which tab the user edited, which is a high-risk UX/data-integrity bug.

Suggested fix:

Before randomization or ROM replacement, use one explicit policy for all editor data:

- Prompt the user to apply or discard unsaved editor changes.
- If applying, call each frame's Save All path and fail visibly if any save fails.
- If discarding, call a proper close/revert hook instead of raw `dispose()`.
- Avoid clearing `EditorDataCache` until any intended cached edits have either been saved or intentionally discarded.

## 7. TM and tutor move assignment dialogs bypass the editor save/revert lifecycle

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/pokemon/editors/EditorUtils.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/TMsSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/Gen5TMsSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/Gen6TMsSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/Gen5MoveTutorsSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/Gen6MoveTutorsSheetPanel.java`

Evidence:

- `EditorUtils.editTMMoves()` passes `romHandler::setTMMoves` into `showMoveAssignmentDialog()`.
- `EditorUtils.editMoveTutorMoves()` passes `romHandler::setMoveTutorMoves` into the same helper.
- `showMoveAssignmentDialog()` calls the supplied setter immediately when the user presses OK.
- The sheet-level `reload()` / `onWindowClosing()` paths only restore compatibility matrices from backups. They do not snapshot or restore the TM/tutor move assignment lists written by these dialogs.

Impact:

Pressing OK in "Edit TM Moves..." or "Edit Tutor Moves..." changes the handler immediately, even if the user later closes the editor without Save All. This is inconsistent with the rest of the editor UI, where changes are supposed to be kept only after Save / Save All. It also means a failed or unintended assignment can be hard to revert from inside the editor.

Suggested fix:

Treat TM/tutor move assignments like the other editor data:

- Keep a working copy in the panel.
- Update table headers from that working copy.
- Apply via `setTMMoves()` / `setMoveTutorMoves()` only during panel save.
- Restore the working copy on reload/close without save.

## 8. Trainer editor hard-codes text limits instead of using handler limits

Severity: Low-Medium

Files:

- `src/com/dabomstew/pkrandom/pokemon/editors/TrainerEditorPanel.java`
- `src/com/dabomstew/pkromio/romhandlers/RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen2RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen6RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen7RomHandler.java`

Evidence:

- `TrainerEditorPanel` hard-codes `MAX_NAME_LEN = 12` and `MAX_CLASS_LEN = 12`.
- `RomHandler` exposes `maxTrainerNameLength()` and `maxTrainerClassNameLength()`.
- Gen 2 reports trainer names up to `Gen2Constants.maxTrainerNameLength` (17).
- Gen 6 and Gen 7 report trainer class names up to 15.
- The class-name dialog truncates edited values to `MAX_CLASS_LEN` before applying them to the working list.

Impact:

The editor can unnecessarily truncate valid trainer text for games that support longer names/classes. For example, Gen 6/7 handler limits allow 15-character class names, but the editor truncates edits at 12 characters.

Suggested fix:

Use the handler-provided limits instead of fixed constants. Also respect `trainerNameMode()` / `maxSumOfTrainerNameLengths()` for Gen 2 style combined trainer-name constraints.

## 9. Gen 5 type-table debug logging is enabled by default

Severity: Low

File:

- `src/com/dabomstew/pkromio/romhandlers/Gen5RomHandler.java`

Evidence:

- `DEBUG_TYPE_TABLE` is initialized from `System.getProperty("upr.debugTypeTable", "true")`, so debug logging is enabled unless the user explicitly disables it.
- Many Fairy/type-table probing paths print diagnostic rows, offsets, and search progress to stdout.

Impact:

Normal Gen 5 ROM loading can produce noisy debug output and slower-looking diagnostics in user logs. This can hide real warnings and make bug reports harder to read.

Suggested fix:

Default the property to `"false"` and gate all type-table diagnostic output behind the flag.

## 10. Single-type starter randomization can reject valid pools or crash on missing type buckets

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/randomizers/StarterRandomizer.java`
- `src/com/dabomstew/pkromio/gamedata/SpeciesSet.java`

Evidence:

- `StarterRandomizer.chooseTypeForStarters()` loops through all types and immediately calls `availableByType.get(type).size()`.
- `SpeciesSet.sortByType(false)` documents that types with no species are absent/null in the returned map.
- The same method uses `> numStartersNeeded`, so a type with exactly the required number of valid starter candidates is rejected.

Impact:

With single-type starter randomization and restrictive options such as no legendaries, no dual types, basic-only, or BST limits, the randomizer can:

- Throw a `NullPointerException` when it checks a type that has no candidates.
- Fail with "No type has X starters available" even when a type has exactly X valid candidates.

Suggested fix:

Handle missing buckets and accept exact fits:

```java
SpeciesSet candidates = availableByType.get(type);
if (candidates != null && candidates.size() >= numStartersNeeded) {
    return type;
}
```

The explicit single-type path should also report a `RandomizationException` if the chosen type has fewer candidates than needed, rather than passing a null set into `chooseStartersBasic()`.

## 11. SpeciesSet random cache reset condition is inverted

Severity: Low-Medium

File:

- `src/com/dabomstew/pkromio/gamedata/SpeciesSet.java`

Evidence:

- `getRandomSpecies()` rebuilds `randomCache` when:

```java
(double) this.size() / (double) randomCache.size() > CACHE_RESET_FACTOR
```

- `CACHE_RESET_FACTOR` is `0.5`, and nearby comments describe resetting when enough removed species have accumulated.
- The current condition rebuilds the cache when it is mostly valid, including immediately after cache creation, but does not rebuild once the live set has shrunk below half of the cached list.

Impact:

Random selection stays correct, but performance can degrade badly when randomizers repeatedly remove from a `SpeciesSet`. Once the live set is small compared with the stale cache, `getRandomSpecies()` can repeatedly pick removed species and spin in the retry loop. This affects starter, trainer, static, wild, and evolution randomizer paths that drain candidate sets.

Suggested fix:

Flip the comparison:

```java
if ((double) this.size() / (double) randomCache.size() < CACHE_RESET_FACTOR) {
    randomCache = new ArrayList<>(this);
}
```

## 12. Starter/static write failures and change tracking are not handled consistently

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/randomizers/StarterRandomizer.java`
- `src/com/dabomstew/pkrandom/randomizers/StaticPokemonRandomizer.java`
- `src/com/dabomstew/pkrandom/GameRandomizer.java`
- `src/com/dabomstew/pkrandom/log/RandomizationLogger.java`
- `src/com/dabomstew/pkromio/romhandlers/RomHandler.java`

Evidence:

- `RomHandler.setStarters(...)` and `RomHandler.setStaticPokemon(...)` return `boolean`, and multiple generation handlers return `false` for unsupported sizes, failed pattern searches, or invalid static data.
- `StarterRandomizer.randomizeStarters()` calls `romHandler.setStarters(pickedStarters)` without checking the return value.
- The custom-starters fast path returns immediately after `setStarters(...)` and never sets `changesMade = true`.
- `StaticPokemonRandomizer.randomizeStaticPokemon()` and `onlyChangeStaticLevels()` call `romHandler.setStaticPokemon(...)` without checking the return value.
- `onlyChangeStaticLevels()` never sets `changesMade = true`.
- `RandomizationLogger` uses `starterRandomizer.isChangesMade()` and `staticPokeRandomizer.isStaticChangesMade()` to decide whether to include starter/static sections in the log overview and details.

Impact:

Several user-visible inconsistencies are possible:

- A handler can reject starter or static writes by returning `false`, while randomization continues as if the write succeeded.
- Non-custom starter randomization can be logged as changed even if `setStarters(...)` failed.
- Fully custom starter selection can successfully change the ROM but be omitted from the log because `changesMade` is never set.
- Static level-only edits can be saved but omitted from the static log section because `onlyChangeStaticLevels()` does not mark changes.

Suggested fix:

Convert failed `setStarters(...)` / `setStaticPokemon(...)` results into `RandomizationException`s, and set `changesMade` only after a successful write. Mark custom starter changes and static level-only changes as changed after their setter succeeds.

## 13. Yellow starter count is still treated as three in trainer and preset paths

Severity: High

Files:

- `src/com/dabomstew/pkromio/romhandlers/Gen1RomHandler.java`
- `src/com/dabomstew/pkrandom/randomizers/TrainerPokemonRandomizer.java`
- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`
- `src/com/dabomstew/pkrandom/Settings.java`

Evidence:

- `Gen1RomHandler.getStarters()` returns only two entries for Yellow: the player Pikachu slot and the rival Eevee slot.
- `Gen1RomHandler.starterCount()` also returns `2` for Yellow.
- `TrainerPokemonRandomizer.randomizeTrainerPokes()` has a special Elite Four / rival-starter path that unconditionally does:

```java
List<Species> starterList = romHandler.getStarters().subList(0, 3);
```

- The GUI can enable `tpRivalCarriesStarterCheckBox` and `tpEliteFourUniquePokemonCheckBox` without checking `starterCount()`, so Yellow can reach this code path.
- `RandomizerGUI.restoreStateFromSettings()` unconditionally applies all three custom starter indexes:

```java
spComboBox1.setSelectedIndex(customStarters[0]);
spComboBox2.setSelectedIndex(customStarters[1]);
spComboBox3.setSelectedIndex(customStarters[2]);
```

- `Settings.tweakForRom()` loops over three custom starter slots and only validates each index against the species list. For Yellow, a third starter index that is valid as a species but invalid for the hidden third starter combo box can survive into GUI restore.

Impact:

Pokemon Yellow can crash during trainer Pokemon randomization when rival carries starter and Elite Four unique Pokemon are enabled, because `subList(0, 3)` is invalid for a two-starter list. A settings or preset load can also crash while restoring the hidden third starter combo box if the stored third starter index is greater than zero.

Suggested fix:

Replace hard-coded starter-slot counts with `romHandler.starterCount()`. For the Elite Four / rival-starter path, use the starters actually returned by `romHandler.getStarters()` or guard the special handling behind `starterCount() >= 3`. In settings/GUI restore, reset or ignore custom starter slots where `slot >= romHandler.starterCount()`.

## 14. Trainer class-name randomization can loop forever with long custom names

Severity: Medium-High

File:

- `src/com/dabomstew/pkrandom/randomizers/TrainerNameRandomizer.java`

Evidence:

- `randomizeTrainerNames()` has bounded retry logic and eventually throws if no valid full trainer-name set can be found.
- `randomizeTrainerClassNames()` does not have the same guard. When replacing an individual class name, it repeatedly picks from the selected custom class-name bucket until the Java character length is short enough:

```java
while (changeTo.length() > maxLength) {
    changeTo = pickFrom.get(random.nextInt(pickFrom.size()));
}
```

- If every entry in `pickFrom` is longer than that trainer class's `maxLength`, the loop has no exit.
- The earlier bucketing code uses `romHandler.internalStringLength(tcName)` to decide which custom names are suitable for the ROM's global maximum, but the final loop uses `String.length()`. For encodings where internal length differs from Java character count, the filtering and final test can disagree.

Impact:

A custom trainer name file can make randomization hang indefinitely instead of failing with a useful error. This is especially likely on ROMs with short per-class name limits, because a custom list can be globally valid while still having no entries that fit a specific class slot.

Suggested fix:

Before choosing a replacement, filter `pickFrom` to names where `romHandler.internalStringLength(name) <= maxLength`. If no entries remain, keep the original class name or throw a `RandomizationException` that asks for shorter custom trainer class names. If random retry logic is still used, give it a hard cap like the trainer-name path.

## 15. AMX script scanning can read outside the buffer or leave a missing script as null state

Severity: Medium

Files:

- `src/com/dabomstew/pkromio/ctr/AMX.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen6RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen7RomHandler.java`

Evidence:

- The `AMX(byte[] data, int scriptNum)` constructor scans every offset for `amxMagic`, then reads the script length from four bytes before the magic:

```java
int length = FileFunctions.readFullInt(data,i-4);
readHeaderAndDecompress(Arrays.copyOfRange(data,i-4,i-4+length));
scriptOffset = i-4;
```

- The scan starts at `i = 0`, so a magic value in the first four bytes makes `i - 4` negative.
- There is no validation that `i >= 4`, that `length` is positive, or that `i - 4 + length <= data.length`.
- If the requested `scriptNum` is not found, the constructor returns without throwing and leaves `decData` unset.
- Gen 6/7 handlers construct AMX scripts and immediately mutate `decData` or call `getBytes()`, for example box legendary, Sea Spirit's Den, Rayquaza, Zygarde, and field-item script paths.

Impact:

A malformed, ROM-hack-altered, or unexpectedly structured script file can fail as an `ArrayIndexOutOfBoundsException`, `IllegalArgumentException`, or later `NullPointerException` instead of a clear ROM parsing error. Because the missing-script case is represented as a half-initialized `AMX`, the failure can happen far away from the actual parse problem.

Suggested fix:

Scan from offset `4`, validate the candidate AMX length before slicing, and throw an `IOException` if the requested script index is not found. Also reject headers where `scriptInstrStart`, `finalOffset`, or `length` point outside `encData`.

## 16. Mini archive unpacking trusts counts and offsets without bounds checks

Severity: Medium

Files:

- `src/com/dabomstew/pkromio/ctr/Mini.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen6RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen7RomHandler.java`

Evidence:

- `Mini.UnpackMini(...)` only checks `fileData == null || fileData.length < 4` before reading the first offset from byte `4`.
- It trusts the archive count and each offset table entry:

```java
int count = FileFunctions.read2ByteInt(fileData, 2);
int start = FileFunctions.readFullInt(fileData, ctr);
...
int end = FileFunctions.readFullInt(fileData, ctr);
int len = end - start;
byte[] data = new byte[len];
System.arraycopy(fileData, start, data, 0, len);
```

- There is no check that the offset table fits in the buffer, that offsets are monotonic, or that `start`/`end` are inside `fileData`.
- Several Gen 6/7 paths dereference the returned array immediately, including move data, encounter data, world data, field items, and berry piles.

Impact:

Corrupt or ROM-hack Mini archives can crash randomization with low-level array exceptions. Even a short buffer with the correct two-byte identifier but fewer than eight bytes can pass the initial validation and then fail while reading the first offset.

Suggested fix:

Validate `fileData.length >= 8`, `4 + ((count + 1) * 4) <= fileData.length`, every offset is between the data section start and `fileData.length`, and each offset is greater than or equal to the previous offset. Return a checked parse failure instead of `null` for malformed archives so callers can report the bad file.

## 17. GARC rebuild writes invalid FATB data for directories with multiple subfiles

Severity: Medium-High

File:

- `src/com/dabomstew/pkromio/ctr/GARCArchive.java`

Evidence:

- The reader supports FATB entries with multiple subfiles. It walks every set bit in the FATB vector and stores each subfile in `entry.subEntries` / `Map<Integer, byte[]>`.
- The writer loops over every subfile in a directory and writes all payload bytes, but emits only one FATB start/end/length triple for the whole directory:

```java
for (int k: directory.keySet()) {
    bitVector |= (1 << k);
    byte[] file = directory.get(k);
    ...
    fimbPayloadStream.write(file);
    totalLength += file.length;
}
...
fatbBuf.putInt(bitVector);
fatbBuf.putInt(fimbOffset);
fimbOffset = fimbPayloadStream.size();
fatbBuf.putInt(fimbOffset);
fatbBuf.putInt(totalLength);
```

- For a bit vector with more than one set bit, the GARC FATB format expects one start/end/length triple per set bit. The current writer also flips `fatbBuf` at the shorter written position while preserving the original `fatb.headerSize`, so the serialized frame length can disagree with its own header.

Impact:

Any edited GARC archive that contains a directory entry with multiple subfiles can be rebuilt with a corrupt FATB frame. The resulting ROM can lose subfile boundaries or fail to load that archive. This is especially risky because the public `files` model exposes subfile maps, so the parser already acknowledges this archive shape.

Suggested fix:

When writing FATB, emit a separate start/end/length triple for each subfile key represented in the bit vector. Recalculate `fatb.headerSize` from the actual number of emitted subentries, and track compression per subfile if mixed compression is possible.

## 18. NDS overlay writes allow an index one past the end

Severity: Low-Medium

File:

- `src/com/dabomstew/pkromio/newnds/NDSRom.java`

Evidence:

- `getOverlay(...)`, `getOverlayAddress(...)`, and `setOverlayRamSize(...)` all guard with `number < arm9overlays.length`.
- `writeOverlay(...)` uses a different guard:

```java
if (number >= 0 && number <= arm9overlays.length) {
    arm9overlays[number].writeOverride(data);
}
```

- When `number == arm9overlays.length`, the condition passes and then indexes one past the last overlay.

Impact:

A bad ROM entry offset, bad config value, or future caller using the overlay count as a sentinel will crash with `ArrayIndexOutOfBoundsException`. This is narrow, but it is a concrete off-by-one in a shared write helper used heavily by Gen 4 and Gen 5 handlers.

Suggested fix:

Change the guard to:

```java
if (number >= 0 && number < arm9overlays.length) {
    arm9overlays[number].writeOverride(data);
}
```

Optionally throw a clear exception for invalid overlay numbers instead of silently ignoring them.

## 19. Gen 6/7 text writes can fail silently

Severity: Medium

Files:

- `src/com/dabomstew/pkromio/romhandlers/Gen6RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen7RomHandler.java`

Evidence:

- Both handlers route 3DS text updates through a private `setStrings(...)` helper.
- The helper catches `IOException`, prints the stack trace, and returns without reporting failure:

```java
try {
    byte[] newRawFile = N3DSTxtHandler.saveEntry(oldRawFile, strings, romEntry.getRomType());
    textGARC.setFile(index, newRawFile);
} catch (IOException e) {
    e.printStackTrace();
}
```

- This helper is used by starter text, item descriptions, trainer names, trainer class names, extra language files, and in-game trade text.
- The surrounding randomization flow continues and can still save the ROM. For the cached main `stringsGarc` / `storyTextGarc`, later save methods write the GARC even if a specific entry update failed.

Impact:

If a 3DS text entry cannot be repacked, the randomizer can silently keep the old text while reporting a successful randomization. This can leave species, trainer, item, or trade text inconsistent with the randomized data, and the user may only see a console stack trace that GUI users normally miss.

Suggested fix:

Let `setStrings(...)` throw `IOException` or wrap it in `RomIOException`. Callers that intentionally want best-effort optional text changes should make that explicit and log a user-visible warning.

## 20. 3DS NCCH rebuild writes header offsets as one byte instead of four

Severity: High

File:

- `src/com/dabomstew/pkromio/ctr/NCCH.java`

Evidence:

- `saveAsNCCH(...)` rebuilds the output 3DS image and updates several NCCH header fields.
- The code uses `RandomAccessFile.write(int)` for offset and length fields:

```java
fNew.seek(0x1A0);
fNew.write((int) newExefsOffset / media_unit_size);
fNew.seek(0x1A4);
fNew.write((int) newExefsLength / media_unit_size);
fNew.seek(0x1B0);
fNew.write((int) newRomfsOffset / media_unit_size);
fNew.seek(0x1B4);
fNew.write((int) newRomfsLength / media_unit_size);
```

- The same pattern is used for the logo and plain-region offsets.
- `RandomAccessFile.write(int)` writes only the low eight bits of the integer. It does not write a four-byte little-endian value.
- Elsewhere in the codebase, multi-byte values are written with helpers such as `FileFunctions.writeFullInt(...)` or explicit byte arrays.

Impact:

Rebuilt 3DS NCCH/CXI output can have corrupt header offsets and lengths for the logo, plain region, ExeFS, and RomFS. Depending on the low byte, a saved ROM may fail to boot, point to the wrong file-system sections, or appear valid until a loader tries to read those sections.

Suggested fix:

Write these fields as four-byte little-endian integers, for example:

```java
byte[] value = new byte[4];
FileFunctions.writeFullInt(value, 0, (int) (newExefsOffset / media_unit_size));
fNew.write(value);
```

or add a local helper like `writeIntLE(RandomAccessFile file, long offset, int value)` and use it for every NCCH header integer.

## 21. NDS and 3DS output writers can leave stale trailing bytes

Severity: Medium

Files:

- `src/com/dabomstew/pkromio/newnds/NDSRom.java`
- `src/com/dabomstew/pkromio/ctr/NCCH.java`

Evidence:

- `NDSRom.saveTo(...)` opens the destination with `new RandomAccessFile(filename, "rw")`.
- It writes the rebuilt ROM through `application_end_offset`, updates header fields and the header CRC, then closes the file.
- It never calls `setLength(0)` after opening and never truncates the file to the rebuilt final size before closing.
- `NCCH.saveAsNCCH(...)` has the same pattern: it opens the output with `"rw"`, writes the rebuilt NCCH image, and closes without truncating.
- Opening an existing file with `RandomAccessFile(..., "rw")` does not truncate it. Existing bytes beyond the last write remain on disk.
- By contrast, the GB/GBC writer uses `FileOutputStream`, which does truncate the destination.

Impact:

If the user overwrites an existing larger `.nds`, `.cxi`, or 3DS output file, stale trailing bytes from the old file can remain after the new ROM image. That can leave the saved file larger than intended and may confuse size checks, hashes, loaders, patch tools, or any downstream logic that expects the physical file length to match the rebuilt image length.

Suggested fix:

Call `fNew.setLength(0)` immediately after opening the destination, or call `fNew.setLength(finalLength)` after the final write. For NDS, the final length should be chosen deliberately from the rebuilt data size or intended padded capacity; for NCCH, use the actual rebuilt NCCH length.

## 22. Random shop placement can exhaust its item list with guaranteed items enabled

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/randomizers/ItemRandomizer.java`
- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`
- `src/com/dabomstew/pkrandom/Settings.java`

Evidence:

- The GUI enables `shGuaranteeEvolutionItemsCheckBox` and `shGuaranteeXItemsCheckBox` whenever random shop items are selected.
- `Settings.tweakForRom(...)` does not validate whether those guaranteed item sets fit the target game's randomizable shop slots.
- `setupNewItems(...)` adds every guaranteed item to `newItems`, subtracts only `guaranteed.size()` from the total special-shop slot count, and does not reject impossible counts.
- `placeNewItems(...)` fills non-main-game shops first, repeatedly calling `newItemsIter.next()` until it finds a non-guaranteed item:

```java
do {
    replacement = newItemsIter.next();
} while (guaranteed.contains(replacement));
```

- If the guaranteed set is too large, or if there are too few non-guaranteed generated items to fill non-main-game shops, the iterator is exhausted and randomization crashes with `NoSuchElementException`.
- If item filters remove all candidate shop items, `setupNewItems(...)` can also reach `remaining.pop()` after adding an empty `possible` set.

Impact:

Certain exposed shop-randomization combinations can fail late during randomization instead of being disabled up front or producing a controlled `RandomizationException`. This is especially risky for games with small randomizable shop pools, many guaranteed evolution/X items, or aggressive filters such as regular-shop and overpowered-item bans.

Suggested fix:

Before building `newItems`, compute:

- total randomizable shop slots;
- randomizable main-game slots;
- randomizable non-main-game slots;
- guaranteed item count;
- non-guaranteed candidate count after all filters.

Reject impossible combinations with a clear `RandomizationException`, or change placement so guaranteed items are explicitly allocated to compatible slots before non-main-game shops are filled.

## 23. NARC filename parsing does not advance after each filename

Severity: Low-Medium

File:

- `src/com/dabomstew/pkromio/newnds/NARCArchive.java`

Evidence:

- The `NARCArchive(byte[] data)` constructor detects FNTB filename payloads with `unk1 == 8`.
- It then loops over `fileCount` filenames, reads a one-byte filename length, increments `offset` by one, copies the filename bytes, and appends the decoded string.
- It never advances `offset` by `fnLength` before reading the next filename:

```java
int fnLength = (fntbframe[offset] & 0xFF);
offset++;
byte[] filenameBA = new byte[fnLength];
System.arraycopy(fntbframe, offset, filenameBA, 0, fnLength);
String filename = new String(filenameBA, StandardCharsets.US_ASCII);
filenames.add(filename);
```

- The writer does advance by `1 + fntbfilename.length` when serializing filenames.

Impact:

Any NARC that actually carries multiple filenames will parse the first filename correctly and then treat bytes from that filename as the next length byte. That can corrupt the in-memory filename table, throw an array bounds exception, or round-trip a malformed FNTB frame when the archive is later saved.

Suggested fix:

After adding each filename, advance the payload offset:

```java
offset += fnLength;
```

Also validate that `offset + fnLength <= fntbframe.length` before copying, so malformed filename tables fail with `IOException`.

## 24. Unique-type starter mode can crash after the initial pool-size check

Severity: Medium

File:

- `src/com/dabomstew/pkrandom/randomizers/StarterRandomizer.java`

Evidence:

- `randomizeStarters(...)` performs an initial sanity check only on the raw number of available species:

```java
if (choosable.size() < starterCount - pickedStarters.size()) {
    throw new RandomizationException("Not enough valid starters");
}
```

- In `UNIQUE` type mode, `chooseUniqueTypeStarters(...)` removes every species sharing a type with already-picked starters, then removes every species sharing a type with each newly picked starter.
- The loop never checks whether `available` is empty before calling `available.getRandomSpecies(random)`.
- `SpeciesSet.getRandomSpecies(...)` throws `IllegalStateException` when the set is empty, not a controlled `RandomizationException`.

Impact:

A pool can contain enough species numerically but not enough distinct type groups to satisfy unique-type starter selection. With restrictive options such as no dual types, BST limits, no legendaries, custom starter preselection, or a small supported/hacked species pool, starter randomization can crash after passing the initial sanity check.

Suggested fix:

Before each unique-type pick, verify that at least one compatible species remains. If not, retry the trio with a fresh shuffled order, backtrack, or throw a `RandomizationException` explaining that the selected restrictions cannot produce unique starter types.

## 25. Static level-only changes can also rewrite formes and linked encounter species

Severity: Medium

File:

- `src/com/dabomstew/pkrandom/randomizers/StaticPokemonRandomizer.java`

Evidence:

- `onlyChangeStaticLevels(...)` is used when static species are unchanged but the static level modifier is enabled.
- After changing levels, it still calls `setSpeciesAndFormeForStaticAndLinkedEncounters(se, se.getSpecies())`.
- That helper:
  - calls `setSpeciesAndFormeForStaticEncounter(...)`, which assigns `sp.getRandomCosmeticFormeNumber(random)`;
  - collapses the species back to its base forme;
  - copies the resulting species and forme onto every linked encounter.

```java
newStatic.setForme(sp.getRandomCosmeticFormeNumber(random));
...
linked.setSpecies(newSpec);
linked.setForme(newForme);
```

- Linked static encounters are documented as potentially differing in level while sharing logical species/forme, and several handlers construct linked encounters from existing ROM data.

Impact:

Choosing only to modify static levels can still re-roll cosmetic formes, collapse an existing alt-form species to its base species, and overwrite linked encounters' species/formes. That violates the expected behavior of a level-only option and can make the output depend on the random seed even when the user did not request static species/forme randomization.

Suggested fix:

In `onlyChangeStaticLevels(...)`, change only `level` and `maxLevel` fields, then save. Do not call the randomization species/forme helper from the level-only path. Also set `changesMade = true` so logging reflects the change.

## 26. Cosmetic forme randomization excludes base formes for real-form-number lists

Severity: Medium

Files:

- `src/com/dabomstew/pkromio/gamedata/Species.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen7RomHandler.java`

Evidence:

- `Species.getRandomCosmeticFormeNumber(...)` is documented as choosing a random cosmetic forme "including itself".
- When `realCosmeticFormNumbers` is empty, `random.nextInt(cosmeticForms)` can return `0`, and the method returns `formeNumber + 0`, so the base form is possible.
- When `realCosmeticFormNumbers` is populated, Gen 7 adds actual cosmetic form numbers to that list, but does not add the base forme number.
- The same method then indexes only `realCosmeticFormNumbers`:

```java
int num = random.nextInt(cosmeticForms);
if (num == cosmeticForms) {
    return formeNumber;
}
...
return realCosmeticFormNumbers.get(num);
```

- The `num == cosmeticForms` branch is unreachable because `Random.nextInt(cosmeticForms)` returns values from `0` through `cosmeticForms - 1`.

Impact:

For species using `realCosmeticFormNumbers`, the base forme is never selected despite the method contract. Any randomizer path using this helper for trainers, wild encounters, statics, or evolutions will over-select cosmetic variants and under-select the base form for those species.

Suggested fix:

Make the base forme an explicit candidate. For example, build a list containing `formeNumber` plus all allowed real cosmetic form numbers and pick from that list. If keeping the existing integer approach, use `random.nextInt(cosmeticForms + 1)` and treat the extra value as the base forme, with a corrected bounds check for `realCosmeticFormNumbers`.

## 27. Full TM/HM compatibility does not mark TM/HM changes

Severity: Low-Medium

Files:

- `src/com/dabomstew/pkrandom/randomizers/TMHMTutorCompatibilityRandomizer.java`
- `src/com/dabomstew/pkrandom/GameRandomizer.java`
- `src/com/dabomstew/pkrandom/log/RandomizationLogger.java`

Evidence:

- `fullTMHMCompatibility()` sets every compatibility flag and writes the map back with `romHandler.setTMHMCompatibility(compat)`.
- Unlike `randomizeTMHMCompatibility()`, `ensureTMCompatSanity()`, `ensureTMEvolutionSanity()`, `fullHMCompatibility()`, and `copyTMCompatibilityToCosmeticFormes()`, it never sets `tmhmChangesMade = true`.

```java
public void fullTMHMCompatibility() {
    Map<Species, boolean[]> compat = romHandler.getTMHMCompatibility();
    ...
    romHandler.setTMHMCompatibility(compat);
}
```

- `GameRandomizer.maybeRandomizeTMHMCompatibility()` only copies TM/HM compatibility to cosmetic formes when `tmhmtCompRandomizer.isTMHMChangesMade()` is true.
- `RandomizationLogger.shouldLogTMHMCompatibility()` and the overview line also rely on the same flag.

Impact:

Selecting full TM/HM compatibility can modify the ROM while the overview and TM/HM compatibility log section still report no TM/HM compatibility change. If a future handler returns only base-species compatibility entries and depends on the copy-to-cosmetic-formes pass, the same missing flag would also skip that propagation.

Suggested fix:

Set `tmhmChangesMade = true` at the end of `fullTMHMCompatibility()`. Add a focused regression test that `FULL` compatibility makes `shouldLogTMHMCompatibility()` reachable or that `isTMHMChangesMade()` is true after the method.

## 28. Metronome-only mode can leave Gen 7 trainer Z-Crystals unusable

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/GameRandomizer.java`
- `src/com/dabomstew/pkrandom/randomizers/SpeciesMovesetRandomizer.java`
- `src/com/dabomstew/pkrandom/randomizers/TrainerPokemonRandomizer.java`

Evidence:

- `GameRandomizer.applyRandomizers()` calls `maybeFixTrainerZCrystals()` before applying Metronome-only mode.
- The Z-Crystal repair is gated on existing moveset/trainer changes:

```java
if (speciesMovesetRandomizer.isChangesMade() || trainerPokeRandomizer.isChangesMade()
        || trainerMovesetRandomizer.isChangesMade()) {
    trainerPokeRandomizer.randomUsableZCrystals();
}
```

- `SpeciesMovesetRandomizer.metronomeOnlyMode()` later rewrites every level-up moveset to Metronome, marks every trainer Pokemon to reset moves, rewrites TMs/tutors to Metronome, and changes Metronome/HM PP.
- That method never sets `changesMade = true`, and even if it did at the end, the Z-Crystal repair has already run.
- `randomUsableZCrystals()` chooses a replacement Z-Crystal from the Pokemon's current/reset moves, so it is the exact repair step needed after a full moveset rewrite.

Impact:

In Gen 7 Metronome-only mode, trainer Pokemon with existing type Z-Crystals can be left holding crystals for their old moves even though their moves reset to Metronome. This can make those held items unusable or misleading in battle. The randomization overview also reports the Pokemon movesets panel as unchanged unless other moveset operations happened.

Suggested fix:

Apply Metronome-only before `maybeFixTrainerZCrystals()`, or run a second Z-Crystal repair immediately after Metronome-only mode. Also set `changesMade = true` in `metronomeOnlyMode()` so overview/log state matches the ROM writes.

## 29. Wild level-only changes are saved but not marked or logged

Severity: Low-Medium

Files:

- `src/com/dabomstew/pkrandom/randomizers/WildEncounterRandomizer.java`
- `src/com/dabomstew/pkrandom/GameRandomizer.java`
- `src/com/dabomstew/pkrandom/log/RandomizationLogger.java`

Evidence:

- `GameRandomizer.maybeRandomizeWildPokemon()` invokes `wildEncounterRandomizer.randomizeEncounters()` when either wild Pokemon are randomized or wild levels are modified.
- In `WildEncounterRandomizer.randomizeEncounters()`, the level-only path returns before `changesMade = true`:

```java
if (!settings.isRandomizeWildPokemon()) {
    modifyLevelsOnly(useTimeOfDay, levelModifier);
    return;
}
...
changesMade = true;
```

- `modifyLevelsOnly(...)` changes encounter levels and calls `romHandler.setEncounters(...)`.
- `RandomizationLogger.shouldLogWildPokemon()` only returns `wildEncounterRandomizer.isChangesMade()`, even though `logWildPokemon()` already has logic for level-only mode.

Impact:

If a user only modifies wild levels, the ROM is changed but the overview and wild Pokemon log section can report no wild encounter changes. In race mode or when comparing logs, this hides a user-requested modification.

Suggested fix:

Set `changesMade = true` after `modifyLevelsOnly(...)` before returning, or make `modifyLevelsOnly(...)` set it itself. Add a test for `isWildLevelsModified() == true` with `isRandomizeWildPokemon() == false`.

## 30. Force-multiple wild encounter repair can ignore user restrictions

Severity: Medium

File:

- `src/com/dabomstew/pkrandom/randomizers/WildEncounterRandomizer.java`

Evidence:

- Normal wild encounter replacement uses a carefully filtered allowed pool that accounts for generation restrictions, no-legendaries, alt-forme settings, ability-dependent formes, irregular formes, area bans, type themes, evolution-stage rules, and similar-strength constraints.
- After an area is randomized, `randomizeArea(...)` calls `enforceMultipleSpecies(area)` for areas marked `forceMultipleSpecies`.
- `enforceMultipleSpecies(...)` bypasses the filtered pool and simply writes a random base species from `RestrictedSpeciesService.getAll(false)`:

```java
while (area.stream().distinct().count() == 1) {
    area.get(0).setSpecies(rSpecService.randomSpecies(random));
}
```

- `RestrictedSpeciesService.randomSpecies(...)` returns from all allowed base species for the generation restrictions, but it does not know the current `noLegendaries`, area ban, type-theme, basic-only, same-stage, similar-strength, or catch-em-all constraints.

Impact:

The duplicate-breaker can introduce a species that the user explicitly excluded from wild encounters, such as a legendary when "block wild legendaries" is enabled, a species banned for that area, or a replacement that violates the selected type/evolution constraints. With very restrictive pools, it can also loop until random chance picks a different species, and if only one base species is available it can loop forever.

Suggested fix:

Pass the current allowed replacement pool into `enforceMultipleSpecies(...)` and pick from that pool after excluding the already-present species. If no legal second species exists, either leave the area unchanged with a warning or throw a controlled `RandomizationException`.

## 31. Race mode check value ignores many randomized output fields

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/CheckValueCalculator.java`
- `src/com/dabomstew/pkrandom/gui/Bundle.properties`

Evidence:

- The UI text says race mode's check value can be shared with a preset "to ensure that everyone has the same ROM to race with."
- `CheckValueCalculator` currently includes:
  - species base stats and abilities;
  - trainer Pokemon levels and species numbers;
  - wild encounter levels and species numbers;
  - TM and tutor move IDs;
  - static and totem species numbers.
- It omits many fields that are randomized and can affect gameplay, including trainer moves, trainer held items, trainer formes, static encounter levels/formes/held items, totem levels/forms/auras/allies/held items, TM/HM and tutor compatibility, in-game trades, shop items, field items, pickup items, palettes, and move data changes.

```java
private void addStaticEncounterInfo() {
    for (StaticEncounter se : romHandler.getStaticPokemon()) {
        addToCV(se.getSpecies().getNumber());
    }
}
```

Impact:

Two different randomized ROMs can produce the same race check value if their differences are only in omitted fields. That weakens the race-mode guarantee and can make a bad preset/seed mismatch look valid to players.

Suggested fix:

Either expand the check value to include every randomized gameplay-relevant field, or narrow the UI copy to state exactly which areas it verifies. At minimum, add the fields controlled by current settings when those settings are enabled.

## 32. CLI direct randomization reports success even when randomization failed

Severity: High

Files:

- `src/com/dabomstew/pkrandom/cli/CliRandomizer.java`
- `src/com/dabomstew/pkrandom/GameRandomizer.java`

Evidence:

- `GameRandomizer.randomize(...)` catches exceptions and stores them in its returned `Results` object:

```java
} catch (Exception e) {
    results.e = e;
}
return results;
```

- `CliRandomizer.performDirectRandomization(...)` discards that result:

```java
GameRandomizer randomizer = new GameRandomizer(settings, null, romHandler, bundle, saveAsDirectory);
randomizer.randomize(filename, verboseLog);
...
System.out.println("Randomized successfully!");
return true;
```

- It also writes the log bytes after discarding the result, so a failed randomization can still produce a log file and a successful process result.

Impact:

The CLI can exit successfully and print "Randomized successfully!" even when randomization, saving, or logging failed inside `GameRandomizer`. Automation that depends on the CLI exit code can treat a missing or partially written ROM as valid.

Suggested fix:

Store the `Results` object, check `wasSaveSuccessful()` and `wasLogSuccessful()`, print the underlying exception, and return false/nonzero on failure. Only write the log and success message after the save result is confirmed.

## 33. Failed ROM opens leave the previous ROM handler active behind a no-ROM UI

Severity: High

File:

- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`

Evidence:

- `openRom(...)` clears the visible UI on a failed open by calling `initialState()`, but it never sets `romHandler = null` in the failure branch:

```java
if (!reinitialize) {
    initialState();
}
...
} else {
    reportOpenRomFailure(f, results);
}
```

- The catch branch has the same shape:

```java
opDialog.setVisible(false);
initialState();
attemptToLogException(e, "GUI.loadFailed", "GUI.loadFailedNoLog", null, null);
```

- `initialState()` resets labels and controls, but it does not clear the `romHandler` field.
- `saveROM()` only checks `if (romHandler == null)`, so a stale handler is still considered a loaded ROM.

Impact:

If a user loads a valid ROM, then tries to open an invalid/unsupported/corrupt file, the window can show "No ROM loaded" while the previous ROM remains active internally. A later randomize/save can operate on the old ROM with freshly reset UI settings, and editor buttons can remain enabled for the old generation until another successful ROM load changes them.

Suggested fix:

Set `romHandler = null` before returning to the no-ROM UI on every non-reinitialize open failure and exception path. Also disable all generation editor buttons in `initialState()` so stale editor buttons cannot survive unload/failure paths.

## 34. Error logging can fail and leave System.err redirected when no ROM is loaded

Severity: Medium

File:

- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`

Evidence:

- `attemptToLogException(...)` only guards the ROM metadata block with `if (this.romHandler != null)`.
- Immediately afterward it calls diagnostics unconditionally:

```java
ps.println("--ROM Diagnostics--");
if (!romHandler.isRomValid(null)) {
    ps.println(bundle.getString("Log.InvalidRomLoaded"));
}
romHandler.printRomDiagnostics(ps);
System.setErr(e1);
ps.close();
```

- The method redirects `System.err` to the error log before those calls:

```java
PrintStream e1 = System.err;
System.setErr(ps);
...
```

- If `romHandler` is null, `romHandler.isRomValid(null)` throws before `System.setErr(e1)` and `ps.close()` run. The outer catch shows the no-log message, but it does not restore `System.err`.

Impact:

Exceptions that happen before a ROM is loaded, or after the UI has been reset to no-ROM state, can lose the intended error log and leave later stderr output redirected to the failed log stream. If a stale previous handler exists, the log can also record diagnostics for the wrong ROM.

Suggested fix:

Use try-with-resources for the error-log `PrintStream` and restore `System.err` in a `finally` block. Only print ROM diagnostics when `romHandler != null`; otherwise record that no ROM was loaded.

## 35. Moves-sheet wrapper tables can show stale side-effect values after edits

Severity: Low

Files:

- `src/com/dabomstew/pkrandom/pokemon/editors/MovesSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/Gen5MovesSheetPanel.java`
- `src/com/dabomstew/pkrandom/pokemon/editors/Gen6MovesSheetPanel.java`

Evidence:

- Each moves sheet builds `frozenModel` and `mainModel` as anonymous wrapper `AbstractTableModel`s that delegate to the real `tableModel`.
- The visible `JTable`s listen to those wrapper models, but the real model is the one that calls `fireTableCellUpdated(...)`.
- No `tableModel.addTableModelListener(...)` bridge exists in these files.
- Gen 5/6 setters modify related fields in the same row. Examples:
  - Editing `COL_MIN_HITS` can raise `move.maxHits`.
  - Editing `COL_MAX_HITS` can lower `move.minHits`.
  - Editing trap turns can set `move.isTrapMove`.
  - Editing status effect can update `move.statusType` and `move.isTrapMove`.
  - Editing crit stage can update `move.criticalChance`.

Impact:

The underlying data changes, but the on-screen cells for related columns can keep showing old values until a manual repaint, scroll, reload, or CSV import refreshes the table. Users can make follow-up edits based on stale visible data, especially in the Gen 5/6 move editors where several columns are derived from each other.

Suggested fix:

Forward events from the inner model to the visible wrapper models, or repaint both tables after any inner model event. The cleaner fix is to keep references to the wrapper `AbstractTableModel`s and call their `fireTableRowsUpdated(...)` or `fireTableDataChanged(...)` when the shared model fires.

## 36. IPS patch files are reported as ZIP files

Severity: Low

File:

- `src/com/dabomstew/pkromio/romio/RomOpener.java`

Evidence:

- `RomOpener.FailType` defines a specific `INVALID_IPS_FILE` value.
- `RandomizerGUI.reportOpenRomFailure(...)` has a dedicated `INVALID_IPS_FILE` message branch.
- `detectInvalidROM(...)` recognizes the IPS signature `"PATCH"` but returns the ZIP failure type:

```java
if (sig[0] == 'P' && sig[1] == 'A' && sig[2] == 'T' && sig[3] == 'C' && sig[4] == 'H') {
    return FailType.INVALID_ZIP_FILE;
}
```

Impact:

Opening an IPS patch produces the wrong error message. That is minor for randomization correctness, but it sends users toward the wrong fix and leaves the IPS-specific failure path unreachable.

Suggested fix:

Return `FailType.INVALID_IPS_FILE` for the `"PATCH"` signature.

## 37. Batch randomization can report completion and advance the index after failed runs

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`
- `src/com/dabomstew/pkrandom/GameRandomizer.java`

Evidence:

- The batch loop calls `saveRandomizedRom(...)` for each output and does not receive a success/failure value.
- `saveRandomizedRom(...)` calls `performRandomization(...)`, which calls `GameRandomizer.randomize(...)` and handles failures internally.
- In batch mode, `performRandomization(...)` starts the randomization thread, joins it, and then returns without surfacing the `Results` object to the batch worker.
- `SwingWorker.done()` always shows the done message and can advance the starting index to `i`:

```java
if (batchRandomizationSettings.shouldAutoAdvanceStartingIndex()) {
    batchRandomizationSettings.setStartingIndex(i);
    attemptWriteConfig();
}
...
JOptionPane.showMessageDialog(frame, bundle.getString("GUI.randomizationDone"));
```

- If a run failure sets `romHandler = null` or throws inside the worker, `done()` still never calls `get()` to observe the background exception before displaying completion.

Impact:

A batch can end with missing or failed output ROMs while still showing the normal completion message. With auto-advance enabled, the configured starting index can move past failed jobs, making reruns skip the failed outputs unless the user manually corrects the index.

Suggested fix:

Make `saveRandomizedRom(...)` or `performRandomization(...)` return a result in batch mode. Stop the loop or collect failures when any output fails, call `get()` in `done()` to observe worker exceptions, and only auto-advance past jobs that actually succeeded.

## 38. Batch randomization races the EDT over the active ROM handler

Severity: High

Files:

- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`

Evidence:

- The batch loop repeatedly reads the shared GUI field `romHandler` while running on a `SwingWorker` background thread:

```java
if (outputType == SaveType.FILE) {
    fileName += '.' + romHandler.getDefaultExtension();
}
...
saveRandomizedRom(outputType, rom);
```

- In batch mode, `performRandomization(...)` joins the worker thread and immediately calls `reinitializeRomHandler(true)`:

```java
if (batchRandomization) {
    t.join();
    reinitializeRomHandler(true);
}
```

- But a successful randomization does not finish synchronously. `performRandomizationInner(...)` only queues `finishRandomization(...)` onto the EDT:

```java
SwingUtilities.invokeLater(() -> finishRandomization(
        filename, seed, cpg, baos, results.getCheckValue(), raceMode, batchRandomization));
```

- `finishRandomization(...)` then mutates the same shared `romHandler` for every success, even in batch mode:

```java
if (this.unloadGameOnSuccess) {
    romHandler = null;
    initialState();
} else {
    reinitializeRomHandler(false);
}
```

- `reinitializeRomHandler(true)` also is not actually synchronous with the handler assignment. It joins the `openRom(...)` background thread, but `openRom(...)` assigns `romHandler = results.getRomHandler()` inside a later `SwingUtilities.invokeLater(...)` callback.

Impact:

A successful batch output can still break the next batch iteration. Depending on EDT timing, `finishRandomization(...)` can null or reload `romHandler` while the background batch loop is preparing the next filename, causing a `NullPointerException`, stale handler use, or skipped outputs. Combined with bug 37, the worker can then report normal completion even though the batch stopped early.

Suggested fix:

Give batch mode a single owner for ROM-handler state. Do not run the normal `finishRandomization(...)` unload/reinitialize block for each batch output, or split out log saving from GUI state mutation. Reload the original ROM synchronously in the batch worker with a method that returns the new handler directly, then assign on one thread before the next iteration starts. Also make `done()` call `get()` so these background exceptions cannot be hidden.

## 39. Manual type-effectiveness edits can become hidden or falsely advertised after state resets

Severity: High

Files:

- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`
- `src/com/dabomstew/pkrandom/GameRandomizer.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen1RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen2RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen3RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen4RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen5RomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/Gen6RomHandler.java`

Evidence:

- Saving the manual type-effectiveness editor writes directly into the currently loaded ROM handler and only tracks the state with a separate boolean:

```java
romHandler.setTypeTable(dialog.getResultTable());
typeEffectivenessManuallyEdited = true;
teUnchangedRadioButton.setSelected(true);
```

- Loading settings/presets clears the boolean and refreshes the button, but it does not restore the handler's original type table:

```java
typeEffectivenessManuallyEdited = false;
refreshTypeEditorButtonLabel();
updateTypeEditorButtonState(romHandler != null && romHandler.hasTypeEffectivenessSupport());
```

- Switching the type-effectiveness mode away from unchanged also clears the boolean in `enableOrDisableSubControls()`, again without restoring the table:

```java
if (!teUnchangedRadioButton.isSelected() && typeEffectivenessManuallyEdited) {
    typeEffectivenessManuallyEdited = false;
}
```

- `GameRandomizer.maybeRandomizeTypeEffectiveness()` does nothing when the setting is `UNCHANGED`, so any manual table already written into the handler remains part of the output ROM:

```java
if (settings.getTypeEffectivenessMod() != Settings.TypeEffectivenessMod.UNCHANGED) {
    ...
}
```

- The opposite desync is also possible after saving with "keep game loaded" enabled. `finishRandomization(...)` reloads the original ROM through `reinitializeRomHandler(false)`, and `openRom(..., true)` skips both `initialState()` and `romLoaded()`, so `typeEffectivenessManuallyEdited` can stay true while the newly assigned handler no longer contains the manual table.

Impact:

Users can produce ROMs with an edited type chart even after loading a preset or selecting another type-effectiveness mode that makes the UI say the chart is default. In the other direction, the UI can keep saying a custom chart is active after the app has reloaded the original ROM and lost that edit. Both cases are dangerous because type-chart changes are global gameplay changes and are hard to notice in normal output checks.

Suggested fix:

Treat the manual type chart as explicit state, not just as a boolean plus direct handler mutation. Keep a copy of the original table or reload it when manual edits are cleared, and reset or reapply manual type edits consistently after settings loads, mode changes, successful saves, and `reinitializeRomHandler(...)`. If manual type edits are not meant to survive presets or reinitialization, clear the button state and registry at the same time the handler is restored.

## 40. Palette randomization can crash on the first species beyond the palette-description list

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/randomizers/Gen3to5PaletteRandomizer.java`

Evidence:

- `getPalettePartDescriptions(...)` converts a species number to a zero-based list index:

```java
int paletteIndex = pk.getNumber() - 1;
```

- The bounds check allows `paletteIndex == paletteDescriptions.size()`:

```java
boolean validIndex = paletteIndex <= paletteDescriptions.size();
```

- The very next line uses that value as a Java list index:

```java
paletteDescriptions.get(paletteIndex)
```

Impact:

When palette randomization reaches the first species whose number is one past the last palette-description entry, it throws `IndexOutOfBoundsException` instead of using `PaletteDescription.BLANK`. That can abort Gen 3-5 palette randomization, especially for ROMs or data sets with extra species/forms or a shorter palette metadata resource.

Suggested fix:

Use `paletteIndex >= 0 && paletteIndex < paletteDescriptions.size()` before indexing the list. Keep the existing blank fallback for out-of-range species and add a regression test with a species number exactly equal to `paletteDescriptions.size() + 1`.

## 41. Main randomization ignores a false save result from the ROM handler

Severity: High

Files:

- `src/com/dabomstew/pkrandom/GameRandomizer.java`
- `src/com/dabomstew/pkromio/romhandlers/AbstractRomHandler.java`
- `src/com/dabomstew/pkromio/romhandlers/AbstractGBRomHandler.java`

Evidence:

- `GameRandomizer.randomize(...)` calls `romHandler.saveRom(...)` but discards its boolean return value:

```java
romHandler.saveRom(filename, seed, saveAsDirectory);
```

- `Results.wasSaveSuccessful()` only checks whether an exception was captured:

```java
public boolean wasSaveSuccessful() {
    return e == null;
}
```

- The save API is explicitly boolean. `AbstractRomHandler.saveRom(...)` returns the value from `saveRomFile(...)` / `saveRomDirectory(...)`:

```java
return saveAsDirectory ? saveRomDirectory(filename) : saveRomFile(filename, seed);
```

- At least one concrete writer can return `false` without throwing. `AbstractGBRomHandler.saveRomFile(...)` catches non-access-denied `IOException` and returns `false`:

```java
} catch (IOException ex) {
    if (ex.getMessage().contains("Access is denied")) {
        throw new CannotWriteToLocationException(...);
    }
    return false;
}
```

Impact:

A GB/GBC output write can fail, return `false`, and still be treated as a successful randomization because no exception was thrown. The GUI can continue into `finishRandomization(...)`, offer/save a log, clear manual edit state, unload or reload the ROM, and show success even though the output file was not written correctly. This is separate from the CLI-specific result handling issue because the core `GameRandomizer.Results` object itself can be wrong.

Suggested fix:

Capture the boolean result from `romHandler.saveRom(...)`. If it is `false`, set `results.e` to a `RomIOException` or `RandomizationException` with the output path and skip result logging. Longer term, remove the boolean save contract and require save methods to throw on failure so callers cannot accidentally ignore it.

## 42. Gen 6 intro Pokemon randomization writes after failed pattern searches

Severity: High

Files:

- `src/com/dabomstew/pkromio/romhandlers/Gen6RomHandler.java`
- `src/com/dabomstew/pkrandom/randomizers/IntroPokemonRandomizer.java`
- `src/com/dabomstew/pkrandom/GameRandomizer.java`

Evidence:

- `Gen6RomHandler.find(...)` returns negative sentinel values for bad, missing, or non-unique patterns:

```java
if (found.isEmpty()) {
    return -1;
} else if (found.size() > 1) {
    return -2;
}
```

- `setIntroPokemon(...)` uses several `find(...)` results without validating them. It immediately adds a prefix length and writes:

```java
int baseAddr = find(code, Gen6Constants.criesTablePrefixXY);
baseAddr += Gen6Constants.criesTablePrefixXY.length() / 2;
...
int croModelOffset = find(introCRO, Gen6Constants.introPokemonModelOffsetXY);
croModelOffset += Gen6Constants.introPokemonModelOffsetXY.length() / 2;
writeWord(introCRO, croModelOffset, introPokemonNum);
```

- The same unvalidated pattern repeats for the initial-cry and repeated-cry offsets.
- If the file write fails, the method catches `IOException`, prints the stack trace, and still returns `true`:

```java
} catch (IOException e) {
    e.printStackTrace();
}
return true;
```

- `IntroPokemonRandomizer.randomizeIntroPokemon()` trusts that return value and marks the change as made:

```java
while (!romHandler.setIntroPokemon(pk)) {
    pk = rSpecService.getAll(true).getRandomSpecies(random);
}
introSpecies = pk;
changesMade = true;
```

Impact:

For XY ROMs where one of the expected CRO/code byte patterns is missing or duplicated, intro randomization can write to the wrong offset near the start of the file, crash with an array bounds error, or silently do nothing while the randomizer logs an intro Pokemon change. Because intro randomization runs even when no user option is enabled, this can affect otherwise minimal randomization runs.

Suggested fix:

Validate every `find(...)` result before deriving write offsets. If a pattern is missing or non-unique, throw `RomIOException` with the pattern name and target file. Do not swallow `IOException`; return `false` only for candidate species that are known unsupported, and throw for infrastructure failures.

## 43. Corrupt batch values in config.ini can crash GUI startup

Severity: Medium

Files:

- `src/com/dabomstew/pkrandom/gui/RandomizerGUI.java`

Evidence:

- `RandomizerGUI` calls `attemptReadConfig()` during construction, before the main UI is fully initialized:

```java
attemptReadConfig();
initExplicit();
```

- The config reader parses batch numeric fields directly:

```java
batchRandomizationSettings.setNumberOfRandomizedROMs(Integer.parseInt(tokens[1].trim()));
...
batchRandomizationSettings.setStartingIndex(Integer.parseInt(tokens[1].trim()));
```

- The method only catches `IOException`:

```java
} catch (IOException ex) {
    ex.printStackTrace();
}
```

Impact:

If `config.ini` contains a malformed value such as `batchrandomization.numberofrandomizedroms=abc` or an out-of-range integer, `Integer.parseInt(...)` throws `NumberFormatException` and escapes the constructor on the EDT. The app can fail to launch until the user manually finds and edits or deletes the config file.

Suggested fix:

Parse config values through small helper methods that catch `NumberFormatException` and validate ranges. On invalid values, keep the default `BatchRandomizationSettings` value and optionally rewrite a clean config after startup.

## Verification Notes

- Findings 38-43 were added from static code review only; I did not recompile or run ROM randomization after these markdown-only updates.
- Compiled all Java sources with `javac` using `flatlaf-3.7.1.jar` and `jna-5.12.1.jar`.
- Compilation succeeded with only deprecation and unchecked warnings.
- The JUnit suite was not run because no build file or JUnit launcher is checked in, and `test/roms` does not contain ROM fixtures.
