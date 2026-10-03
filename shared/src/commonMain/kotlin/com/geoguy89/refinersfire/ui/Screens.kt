package com.geoguy89.refinersfire.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.geoguy89.refinersfire.ThemeId
import com.geoguy89.refinersfire.data.Store
import com.geoguy89.refinersfire.game.GameEngine
import com.geoguy89.refinersfire.game.Difficulty
import com.geoguy89.refinersfire.game.GameMode
import com.geoguy89.refinersfire.game.GameState
import com.geoguy89.refinersfire.game.Glyph
import com.geoguy89.refinersfire.game.Piece
import com.geoguy89.refinersfire.game.Ranks
import com.geoguy89.refinersfire.game.StoneColor
import com.geoguy89.refinersfire.gfx.Palette
import com.geoguy89.refinersfire.gfx.drawGoldTile
import com.geoguy89.refinersfire.gfx.drawLeadTile
import com.geoguy89.refinersfire.gfx.drawPiece
import com.geoguy89.refinersfire.gfx.drawSeal
import com.geoguy89.refinersfire.formatDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ---- Title ----------------------------------------------------------------------------------------------------------

@Composable
fun TitleScreen(vm: GameViewModel) {
    Box(Modifier.fillMaxSize().woodBackground()) {
        // The great seal slowly turns behind the title, with stones orbiting it.
        Canvas(Modifier.fillMaxSize()) {
            val t = vm.fx.now
            // Centre the seal on the screen and size it so the orbiting stones (out to ~1.28r) always stay
            // on screen, even on near-square displays such as an unfolded Fold.
            val r = minOf(size.minDimension * 0.38f, (size.height / 2f - 28.dp.toPx()) / 1.28f)
            val c = Offset(size.width / 2, size.height * 0.5f)
            drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0x44FFB040), Color.Transparent), c, r * 1.3f), r * 1.3f, c)
            drawSeal(c, r, t * 6f, alpha = 0.7f)
            val colors = StoneColor.entries
            Glyph.entries.forEachIndexed { i, g ->
                val a = i * 2 * PI.toFloat() / 12 - t * 0.12f
                val rr = r * (1.12f + 0.03f * sin(t * 1.5f + i))
                val p = c + Offset(cos(a), sin(a)) * rr
                drawPiece(Piece.Stone(g, colors[i % colors.size]), p, r * 0.2f, alpha = 0.85f)
            }
        }
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        ) {
            Logo(vm, 64.sp)
            Text("Burn away the dross", style = bodyStyle(18.sp, Palette.parchment, bold = true))
            Box(Modifier.height(24.dp))
            val buttons = Modifier.widthIn(min = 240.dp, max = 320.dp).fillMaxWidth()
            vm.savedChallenge?.let { c ->
                BrassButton("Continue Challenge vs ${c.challengeRival ?: "friend"}", vm::resumeChallenge, buttons, fontSize = 16.sp)
            }
            val saved = vm.savedGame
            if (saved != null) {
                BrassButton("Continue · Board ${saved.board}", vm::resumeSavedGame, buttons, fontSize = 18.sp)
            }
            BrassButton("New Game", { vm.push(Overlay.NewGame) }, buttons, fontSize = 18.sp)
            // Today's Manna: golden until it's gathered, then a quiet record of the day's score and the streak.
            val manna = vm.todaysManna
            val streak = vm.mannaStreak
            BrassButton(
                when {
                    manna != null -> "Today's Manna: ${manna.score}" + if (streak > 1) " · $streak days" else ""
                    vm.savedManna?.mannaDay == vm.today -> "Continue Today's Manna"
                    streak > 0 -> "Today's Manna · keep your $streak-day streak"
                    else -> "Today's Manna"
                },
                vm::openManna, buttons, dark = manna != null, fontSize = 16.sp,
            )
            Row(buttons, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val solved = vm.puzzleStars.size
                BrassButton(if (solved > 0) "Puzzles · $solved" else "Puzzles", vm::openPuzzles, Modifier.weight(1f), dark = true, fontSize = 15.sp)
                BrassButton("How to Play", { vm.push(Overlay.HowToPlay) }, Modifier.weight(1f), dark = true, fontSize = 15.sp)
            }
            BrassButton("Hall of Fame", { vm.openHallOfFame() }, buttons, dark = true)
            val social = vm.online.incoming.size + vm.totalUnread + vm.ourMoves.size
            val on = vm.online.friends.count { it.online }
            val label = buildString {
                append("Friends & 1v1")
                if (social > 0) append(" ($social)")
                if (on > 0) append(" · $on online")
            }
            BrassButton(label, vm::openFriends, buttons, dark = true)
            val fresh = vm.unseenAchievements.size
            BrassButton(if (fresh > 0) "Achievements ($fresh new)" else "Achievements", vm::openAchievements, buttons, dark = true)
            BrassButton("Options", { vm.push(Overlay.Options) }, buttons, dark = true)
        }
        // Hebrews 11:1, New Living Translation.
        Column(
            Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(start = 24.dp, end = 24.dp, bottom = 12.dp).widthIn(max = 620.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "\u201CFaith is the confidence that what we hope for will actually happen; it gives us assurance about things we " +
                    "cannot see.\u201D",
                style = bodyStyle(12.sp, Palette.parchment.copy(alpha = 0.8f)).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
                textAlign = TextAlign.Center,
            )
            Text("Hebrews 11:1 (NLT)", style = bodyStyle(11.sp, Palette.goldLight.copy(alpha = 0.85f), bold = true), modifier = Modifier.padding(top = 4.dp))
        }
    }
}

// ---- Overlay host ---------------------------------------------------------------------------------------------------

@Composable
fun OverlayHost(vm: GameViewModel) {
    when (val o = vm.overlay) {
        null -> Unit
        Overlay.NewGame -> NewGamePanel(vm)
        Overlay.Options -> OptionsPanel(vm)
        Overlay.HighScores -> HallOfFamePanel(vm)
        Overlay.HowToPlay -> HowToPlayPanel(vm)
        Overlay.Pause -> PausePanel(vm)
        Overlay.ConfirmAbandonMatch -> GamePanel("Abandon Match?", vm::pop) {
            val rival = vm.match?.opponent?.name ?: "Your opponent"
            Text("$rival wins the match automatically.", style = bodyStyle(), textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrassButton("Keep Playing", vm::pop, Modifier.weight(1f), dark = true)
                BrassButton("Abandon", vm::leaveMatch, Modifier.weight(1f))
            }
        }
        Overlay.ConfirmQuit -> GamePanel("Abandon Game?", vm::pop) {
            Text("Your progress on this game will be lost.", style = bodyStyle(), textAlign = TextAlign.Center)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrassButton("Keep Playing", vm::pop, Modifier.weight(1f), dark = true)
                BrassButton("New Game", { vm.startNewGame(vm.settings.difficulty, vm.settings.mode) }, Modifier.weight(1f))
            }
        }
        is Overlay.BoardComplete -> BoardCompletePanel(vm, o)
        is Overlay.GameOver -> GameOverPanel(vm, o)
        Overlay.Friends -> FriendsPanel(vm)
        is Overlay.Challenge -> ChallengePanel(vm, o.rival)
        is Overlay.PlayerCard -> PlayerCardPanel(vm, o.entry)
        Overlay.MatchLobby -> MatchLobbyPanel(vm)
        Overlay.ConfirmLeaveMatch -> ConfirmLeaveMatchPanel(vm)
        Overlay.MatchOver -> MatchOverPanel(vm)
        is Overlay.Chat -> ChatPanel(vm, o.friendId)
        Overlay.ChangeName -> NamePanel(vm, firstTime = false)
        Overlay.ReportBug -> ReportBugPanel(vm)
        Overlay.Update -> UpdatePanel(vm)
        Overlay.Peek -> PeekPanel(vm)
        is Overlay.AsyncSetup -> AsyncSetupPanel(vm, o.rival)
        is Overlay.AsyncDone -> AsyncDonePanel(vm, o)
        Overlay.Achievements -> AchievementsPanel(vm)
        Overlay.Manna -> MannaPanel(vm)
        Overlay.GatheringSetup -> GatheringSetupPanel(vm)
        Overlay.Puzzles -> PuzzlesPanel(vm)
        is Overlay.PuzzleDone -> PuzzleDonePanel(vm, o)
        is Overlay.MannaDone -> MannaDonePanel(vm, o)
    }
}

@Composable
private fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        for (o in options) {
            BrassButton(label(o), { onSelect(o) }, Modifier.weight(1f), dark = o != selected, fontSize = 15.sp)
        }
    }
}

@Composable
private fun NewGamePanel(vm: GameViewModel) {
    var difficulty by rememberSaveable { mutableStateOf(vm.settings.difficulty) }
    var mode by rememberSaveable { mutableStateOf(vm.settings.mode) }
    GamePanel("New Game", vm::pop) {
        Text("First time players should try the tutorial before playing.", style = bodyStyle(14.sp), textAlign = TextAlign.Center)
        Text("Select a Difficulty Level", style = bodyStyle(15.sp, Palette.goldLight, bold = true))
        Choice(Difficulty.entries, difficulty, { it.displayName }) { vm.click(); difficulty = it }
        Text(
            "Begins on board ${difficulty.startBoard} · points x${GameEngine.multiplier(difficulty, mode)}",
            style = bodyStyle(13.sp, Palette.parchment.copy(alpha = 0.75f)),
        )
        Text("Choose Your Game Mode", style = bodyStyle(15.sp, Palette.goldLight, bold = true))
        for (pair in GameMode.entries.chunked(2)) Choice(pair, mode, { it.displayName }) { vm.click(); mode = it }
        Text(mode.blurb, style = bodyStyle(13.sp, Palette.parchment.copy(alpha = 0.75f)), textAlign = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            BrassButton("Tutorial", { vm.push(Overlay.HowToPlay) }, Modifier.weight(1f), dark = true)
            BrassButton("Play!", { vm.startNewGame(difficulty, mode) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun OptionsPanel(vm: GameViewModel) {
    val s = vm.settings
    val sliderColors = SliderDefaults.colors(thumbColor = Palette.goldLight, activeTrackColor = Palette.gold, inactiveTrackColor = Palette.brassDark)
    val switchColors = SwitchDefaults.colors(checkedThumbColor = Palette.goldLight, checkedTrackColor = Palette.brassDark, uncheckedThumbColor = Palette.stone, uncheckedTrackColor = Palette.stoneDark)
    GamePanel("Options", vm::pop) {
        Text("Sound Effects", style = bodyStyle(15.sp, bold = true), modifier = Modifier.fillMaxWidth())
        Slider(s.sfxVolume, { vm.updateSettings(s.copy(sfxVolume = it)) }, colors = sliderColors, onValueChangeFinished = vm::click)
        Text("Music", style = bodyStyle(15.sp, bold = true), modifier = Modifier.fillMaxWidth())
        Slider(s.musicVolume, { vm.updateSettings(s.copy(musicVolume = it)) }, colors = sliderColors)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Player Name", style = bodyStyle(15.sp, bold = true))
                Text(s.playerName, style = bodyStyle(14.sp, Palette.goldLight))
            }
            BrassButton("Change", { vm.push(Overlay.ChangeName) }, Modifier.width(110.dp), dark = true, fontSize = 14.sp, minHeight = 38.dp)
        }
        OptionSwitch("Vibration", s.haptics, switchColors) { vm.click(); vm.updateSettings(s.copy(haptics = it)) }
        if (vm.push.supported) {
            OptionSwitch("Notifications", s.notifications, switchColors) { vm.setNotifications(it) }
            Text(
                "Chats, challenges, pokes and friend requests when the game isn't open on screen.",
                style = bodyStyle(12.sp, Palette.parchment.copy(alpha = 0.7f)), textAlign = TextAlign.Center,
            )
            if (s.notifications) {
                OptionSwitch("Friends' daily Manna", s.notifyManna, switchColors) { vm.setNotifyManna(it) }
                Text(
                    "A nudge when a friend gathers the day's Manna and you haven't yet.",
                    style = bodyStyle(12.sp, Palette.parchment.copy(alpha = 0.7f)), textAlign = TextAlign.Center,
                )
            }
        } else if (com.geoguy89.refinersfire.isWeb) {
            Text(
                "On iPhone and iPad, notifications work once the game is on your Home Screen. Chats, challenges and requests always show up here in the game.",
                style = bodyStyle(12.sp, Palette.parchment.copy(alpha = 0.7f)), textAlign = TextAlign.Center,
            )
        }
        if (vm.updater.supported) {
            OptionSwitch("Check for updates", s.checkUpdates, switchColors) { vm.click(); vm.updateSettings(s.copy(checkUpdates = it)) }
            BrassButton("Check Now", { vm.click(); vm.checkForUpdate(manual = true) }, Modifier.fillMaxWidth(), dark = true, fontSize = 14.sp, minHeight = 38.dp)
        }
        Text("Theme", style = bodyStyle(15.sp, bold = true), modifier = Modifier.fillMaxWidth())
        // Five themes: three, then two. Starlight opens after seven days of Manna.
        for (row in ThemeId.entries.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (t in row) {
                    val locked = !vm.themeUnlocked(t)
                    BrassButton(
                        if (locked) "${t.displayName} (locked)" else t.displayName, { vm.chooseTheme(t) }, Modifier.weight(1f),
                        dark = t != s.theme, fontSize = 14.sp, minHeight = 40.dp,
                    )
                }
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Text(s.theme.blurb, style = bodyStyle(13.sp, Palette.parchment.copy(alpha = 0.75f)), textAlign = TextAlign.Center)
        Text("Pieces", style = bodyStyle(15.sp, bold = true), modifier = Modifier.fillMaxWidth())
        val pieceChoices = listOf<ThemeId?>(null, ThemeId.MODERN, ThemeId.TEMPLE, ThemeId.FUTURE, ThemeId.GARDEN)
        for (row in pieceChoices.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (p in row) BrassButton(pieceSetLabel(p), { vm.click(); vm.updateSettings(s.copy(pieceSet = p)) }, Modifier.weight(1f), dark = p != s.pieceSet, fontSize = 14.sp, minHeight = 40.dp)
                repeat(3 - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Text("Share Scores", style = bodyStyle(15.sp, bold = true), modifier = Modifier.fillMaxWidth())
        // Local (same Wi-Fi) sharing isn't possible from a web page.
        val shareModes = if (com.geoguy89.refinersfire.isWeb) ShareMode.entries - ShareMode.LOCAL else ShareMode.entries
        Choice(shareModes, vm.shareMode, { it.label }) { vm.setShareMode(it) }
        Text(
            when (vm.shareMode) {
                ShareMode.HIDDEN -> "Your scores stay on this device (friends you add still see them). The server keeps no record of when you play: friends see \"Activity hidden\" and you never show as online."
                ShareMode.LOCAL -> "Swap your own high scores with anyone playing on the same Wi-Fi. Nothing you receive is passed on, and yours go no further than the people you meet."
                ShareMode.PLUS -> "Everything Local does, plus: you're on the ranked Global leaderboard, other Global players can add you or challenge you (and you them), and nearby Global players pass your scores on. Local-only players' scores are never passed on."
            },
            style = bodyStyle(12.sp, Palette.parchment.copy(alpha = 0.7f)), textAlign = TextAlign.Center,
        )
        BrassButton("Report a Bug", { vm.click(); vm.push(Overlay.ReportBug) }, Modifier.fillMaxWidth(), dark = true, fontSize = 14.sp, minHeight = 38.dp)
        BrassButton("Done", vm::pop, Modifier.fillMaxWidth())
        Text(com.geoguy89.refinersfire.AppVersion.label, style = bodyStyle(11.sp, Palette.parchment.copy(alpha = 0.55f)), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ReportBugPanel(vm: GameViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    GamePanel("Report a Bug", vm::pop) {
        Text("What went wrong? What were you doing when it happened?", style = bodyStyle(14.sp), textAlign = TextAlign.Center)
        OutlinedTextField(
            text, { text = it.take(1500) }, minLines = 4, maxLines = 8,
            label = { Text("Describe the problem", style = bodyStyle(12.sp)) },
            textStyle = bodyStyle(15.sp),
            keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.gold, unfocusedBorderColor = Palette.brassDark, cursorColor = Palette.goldLight,
                focusedLabelColor = Palette.goldLight, unfocusedLabelColor = Palette.parchment,
                focusedTextColor = Palette.parchment, unfocusedTextColor = Palette.parchment,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Your player name, the game version and a short log of what the app did recently are sent with it. Chats are never included.",
            style = bodyStyle(12.sp, Palette.parchment.copy(alpha = 0.7f)), textAlign = TextAlign.Center,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrassButton("Cancel", vm::pop, Modifier.weight(1f), dark = true)
            BrassButton(if (vm.bugSending) "Sending..." else "Send", { vm.reportBug(text.trim()) }, Modifier.weight(1f), enabled = text.isNotBlank() && !vm.bugSending)
        }
    }
}

private fun pieceSetLabel(t: ThemeId?) = when (t) {
    null -> "Theme"
    ThemeId.MODERN -> "Stones"
    ThemeId.TEMPLE -> "Temple"
    ThemeId.FUTURE -> "Shapes"
    ThemeId.GARDEN -> "Plants"
    ThemeId.STARLIGHT -> "Stones"
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, colors: androidx.compose.material3.SwitchColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = bodyStyle(15.sp, bold = true), modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = colors)
    }
}

@Composable
private fun PausePanel(vm: GameViewModel) {
    GamePanel("Paused", vm::pop) {
        vm.state?.let { StatsTable(it) }
        val m = Modifier.fillMaxWidth()
        BrassButton("Resume", vm::pop, m)
        // A live match can't be saved and resumed: quitting abandons it.
        val inMatch = vm.match != null
        val inPuzzle = vm.state?.puzzleId != null
        if (inPuzzle) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BrassButton("Retry Puzzle", vm::retryPuzzle, Modifier.weight(1f), dark = true, fontSize = 14.sp)
                BrassButton("All Puzzles", vm::leavePuzzle, Modifier.weight(1f), dark = true, fontSize = 14.sp)
            }
        } else if (!inMatch) BrassButton("New Game", { vm.push(Overlay.ConfirmQuit) }, m, dark = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrassButton("How to Play", { vm.push(Overlay.HowToPlay) }, Modifier.weight(1f), dark = true, fontSize = 14.sp)
            BrassButton("Hall of Fame", { vm.openHallOfFame() }, Modifier.weight(1f), dark = true, fontSize = 14.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrassButton("Options", { vm.push(Overlay.Options) }, Modifier.weight(1f), dark = true, fontSize = 14.sp)
            BrassButton("Achievements", vm::openAchievements, Modifier.weight(1f), dark = true, fontSize = 14.sp)
        }
        val group = vm.match?.let { it.isCoop || it.isGathering } == true
        if (inMatch && group) BrassButton(if (vm.match?.isCoop == true) "Leave Co-op" else "Leave Gathering", vm::leaveMatch, m, dark = true)
        else if (inMatch) BrassButton("Abandon Match", { vm.push(Overlay.ConfirmAbandonMatch) }, m, dark = true)
        else if (!inPuzzle) BrassButton("Save & Quit to Title", vm::quitToTitle, m, dark = true)
    }
}

private fun formatTime(ms: Long): String {
    val s = ms / 1000
    fun two(n: Long) = n.toString().padStart(2, '0')
    return if (s >= 3600) "${s / 3600}:${two(s / 60 % 60)}:${two(s % 60)}" else "${s / 60}:${two(s % 60)}"
}

@Composable
private fun StatsTable(s: GameState) {
    val next = Ranks.nextRank(s.score)
    val rows = listOfNotNull(
        "Score" to s.score.toString(),
        "Rank" to s.rank,
        next?.let { "Next title" to "${it.second} in ${it.first - s.score}" },
        "Game time" to formatTime(s.elapsedMillis),
        "Boards cleared" to s.boardsCleared.toString(),
        "Longest no-discard streak" to "${s.bestStreak} stones",
        "Stones placed" to s.stonesPlaced.toString(),
        "Lines refined" to s.linesCleared.toString(),
        "Discards" to s.discards.toString(),
        "Hints used" to s.hintsUsed.toString(),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for ((k, v) in rows) Row(Modifier.fillMaxWidth()) {
            Text(k, style = bodyStyle(14.sp, Palette.parchment.copy(alpha = 0.8f)), modifier = Modifier.weight(1f))
            Text(v, style = bodyStyle(14.sp, Palette.goldLight, bold = true), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun BoardCompletePanel(vm: GameViewModel, o: Overlay.BoardComplete) {
    GamePanel("Board ${o.event.completedBoard} Complete!", null) {
        Text("The lead is gold! +${o.event.points} points", style = bodyStyle(16.sp, Palette.goldLight, bold = true), textAlign = TextAlign.Center)
        Text("You are now a", style = bodyStyle(14.sp))
        Text(o.event.stats.rank, style = titleStyle(26.sp), textAlign = TextAlign.Center)
        StatsTable(o.event.stats)
        BrassButton("Onward to Board ${o.event.completedBoard + 1}", vm::continueAfterBoard, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GameOverPanel(vm: GameViewModel, o: Overlay.GameOver) {
    GamePanel("The Forge Overflows", null) {
        Text("Game over. Your final rank:", style = bodyStyle(15.sp), textAlign = TextAlign.Center)
        Text(o.final.rank, style = titleStyle(28.sp), textAlign = TextAlign.Center)
        StatsTable(o.final)
        if (o.qualifies) {
            Text("${vm.settings.playerName} will be inscribed in the Hall of Fame!", style = bodyStyle(14.sp, Palette.goldLight, bold = true), textAlign = TextAlign.Center)
            BrassButton("Inscribe My Name", { vm.finishGameOver(true) }, Modifier.fillMaxWidth())
        } else {
            BrassButton("Return to Title", { vm.finishGameOver(false) }, Modifier.fillMaxWidth())
        }
    }
}

/**
 * Choosing the player name. The first time it can't be dismissed and starts empty; from Options it starts with the
 * current name and can be cancelled.
 */
@Composable
fun NamePanel(vm: GameViewModel, firstTime: Boolean) {
    // The cursor starts after the current name, so Backspace edits it (on the web a tap can leave it at the start).
    var field by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        val initial = if (firstTime) "" else vm.settings.playerName
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val name = field.text
    val valid = GameViewModel.cleanName(name) != null
    GamePanel(if (firstTime) "Choose Your Player Name" else "Change Your Name", if (firstTime) null else vm::pop) {
        if (firstTime) Text("Welcome, refiner! What should we call you?", style = bodyStyle(15.sp), textAlign = TextAlign.Center)
        val installHint = remember { if (firstTime) com.geoguy89.refinersfire.installFirstHint() else null }
        if (installHint != null) Text(installHint, style = bodyStyle(13.sp, Palette.goldLight), textAlign = TextAlign.Center)
        OutlinedTextField(
            field, { if (it.text.length <= GameViewModel.NAME_MAX) field = it }, singleLine = true,
            label = { Text("Player name", style = bodyStyle(12.sp)) },
            textStyle = bodyStyle(18.sp, bold = true),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words),
            keyboardActions = KeyboardActions(onDone = { if (valid) vm.choosePlayerName(name) }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Palette.gold, unfocusedBorderColor = Palette.brassDark, cursorColor = Palette.goldLight,
                focusedLabelColor = Palette.goldLight, unfocusedLabelColor = Palette.parchment,
                focusedTextColor = Palette.parchment, unfocusedTextColor = Palette.parchment,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Other users will see you by this name",
            style = bodyStyle(13.sp, Palette.parchment.copy(alpha = 0.75f)).copy(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic),
            textAlign = TextAlign.Center,
        )
        Text("${name.length} / ${GameViewModel.NAME_MAX}", style = bodyStyle(11.sp, Palette.parchment.copy(alpha = 0.5f)))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!firstTime) BrassButton("Cancel", vm::pop, Modifier.weight(1f), dark = true)
            BrassButton(if (firstTime) "Continue" else "Save", { vm.choosePlayerName(name) }, Modifier.weight(1f), enabled = valid)
        }
    }
}

@Composable
private fun UpdatePanel(vm: GameViewModel) {
    val info = vm.update ?: return
    val progress = vm.updateProgress
    GamePanel("Update Available", if (progress == null) vm::pop else null) {
        Text("Refiner's Fire ${info.version} (build ${info.build}) is available.", style = bodyStyle(16.sp, Palette.goldLight, bold = true), textAlign = TextAlign.Center)
        Text(
            "Your scores, friends and achievements are kept. The game downloads the update, installs it and closes;" +
                " tap the \"updated\" notification (or the app icon) to carry on. The very first time, Android asks" +
                " you to allow installs from Refiner's Fire.",
            style = bodyStyle(13.sp, Palette.parchment.copy(alpha = 0.8f)), textAlign = TextAlign.Center,
        )
        if (progress != null) {
            Text("Downloading... ${(progress * 100).toInt()}%", style = bodyStyle(14.sp))
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(10.dp)) {
                drawRoundRect(Palette.stoneDark, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                drawRoundRect(Palette.gold, size = androidx.compose.ui.geometry.Size(size.width * progress, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            }
        } else {
            BrassButton("Update Now", vm::installUpdate, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrassButton("Later", vm::pop, Modifier.weight(1f), dark = true, fontSize = 14.sp)
                BrassButton("Skip This Version", vm::skipUpdate, Modifier.weight(1f), dark = true, fontSize = 14.sp)
            }
        }
    }
}

// ---- How to play ----------------------------------------------------------------------------------------------------

private class HelpPage(val title: String, val text: String, val art: (androidx.compose.ui.graphics.drawscope.DrawScope, Float) -> Unit)

private val redLapis = Piece.Stone(Glyph.LAPIS, StoneColor.RED)
private val redTurquoise = Piece.Stone(Glyph.TURQUOISE, StoneColor.RED)
private val greenLapis = Piece.Stone(Glyph.LAPIS, StoneColor.GREEN)
private val blueCarnelian = Piece.Stone(Glyph.CARNELIAN, StoneColor.BLUE)

/** Draw a small strip of tiles with pieces for help illustrations. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.strip(pieces: List<Piece?>, gold: List<Boolean>, t: Float, marks: Map<Int, Boolean> = emptyMap()) {
    val n = pieces.size
    val cell = minOf(size.width / n, size.height)
    val ox = (size.width - cell * n) / 2
    val oy = (size.height - cell) / 2
    for (i in 0 until n) {
        val tl = Offset(ox + i * cell, oy)
        if (gold[i]) drawGoldTile(tl, cell, t, i * 0.1f) else drawLeadTile(tl, cell, i + 3)
        pieces[i]?.let { drawPiece(it, tl + Offset(cell / 2, cell / 2), cell, time = t) }
        marks[i]?.let { ok ->
            drawRect(if (ok) Palette.hint else Palette.invalid, tl, androidx.compose.ui.geometry.Size(cell, cell), alpha = 0.9f, style = androidx.compose.ui.graphics.drawscope.Stroke(cell * 0.06f))
        }
    }
}

private val helpPages = listOf(
    HelpPage(
        "The Refining",
        "Your task is to refine a board of lead into gold. A square turns to gold when the stones on its row or column are cleared. Turn every square to gold to complete the board.",
    ) { d, t ->
        val p = (t * 0.5f) % 1f
        d.strip(List(5) { null }, List(5) { it.toFloat() / 5f < p }, t)
    },
    HelpPage(
        "Placing Stones",
        "Each stone must be placed next to another stone — above, below, left or right; diagonals don't count. Every stone it touches must share its colour or its shape.",
    ) { d, t -> d.strip(listOf(redLapis, redTurquoise, greenLapis, null, blueCarnelian), List(5) { false }, t, mapOf(1 to true, 2 to true)) },
    HelpPage(
        "Mismatches",
        "A stone must match everything it touches, so a red stone can't sit between a green one and a blue one of other shapes. Squares touching several different stones — joints — are the hardest to fill.",
    ) { d, t -> d.strip(listOf(greenLapis, redTurquoise, blueCarnelian), List(3) { false }, t, mapOf(1 to false)) },
    HelpPage(
        "Refining",
        "Fill an entire row or column and its stones vanish, leaving the squares gold. Clearing a line also empties the forge. Each new board brings more shapes and colours.",
    ) { d, t -> d.strip(listOf(redLapis, redTurquoise, Piece.Cornerstone, blueCarnelian, null), listOf(false, false, false, false, true), t) },
    HelpPage(
        "The Forge",
        "If a stone has no home — or you'd rather wait for a better one — tap Discard or the forge to throw it in. Each discard fills the forge one level; each placement cools it by one. Discard when the forge is full and the game is over.",
    ) { d, t ->
        val c = Offset(d.size.width / 2, d.size.height / 2)
        val r = d.size.height * 0.45f
        d.drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Palette.goldLight, Palette.ember, Palette.lava, Color.Transparent), c, r), r, c)
        d.drawPiece(redTurquoise, c + Offset(0f, -r * 0.3f + (t % 1.5f) * r * 0.4f), r, alpha = 1f - ((t % 1.5f) / 1.5f))
    },
    HelpPage(
        "Cornerstone & Hammer",
        "The Cornerstone matches anything, and anything may be placed beside it. The Refiner's Hammer knocks out any piece you choose — ideal for clearing a stubborn joint. Using either cools the forge.",
    ) { d, t -> d.strip(listOf(Piece.Cornerstone, null, Piece.Hammer), List(3) { false }, t) },
    HelpPage(
        "Scoring",
        "A stone on a lead square earns 5, 15, 30 or 50 points as it touches 1 to 4 stones; on a gold square only 1 to 4. Each cleared line earns 50, a finished board 500. Average doubles every score, Hard quadruples it, and Time Trial doubles it again.",
    ) { d, t -> d.strip(listOf(redLapis, redTurquoise, null, redLapis), listOf(false, false, false, true), t) },
    HelpPage(
        "Symbol Lines",
        "Clear a line where every stone is the same shape for a 250 point bonus. Make them the same colour too and it's perfectly refined: 1,000 points. A line of one colour alone earns no bonus.",
    ) { d, t -> d.strip(listOf(redLapis, redLapis, redLapis, redLapis, redLapis), List(5) { false }, t) },
    HelpPage(
        "Hints & Penalties",
        "Stuck? The Hint button lights up every square the stone can go, but stokes the forge (in green): one level for the first hint on a board, two for the second, and it can't cool below one until that board is cleared. You get two hints per board and the button counts them down; tapping squares to guess also stokes it (in violet) on every second wrong try. Melting a stone in the forge costs points. Every 10 stones placed without a discard is a streak; throwing away the Hammer doesn't break it and costs no points. Rise from Dross to Pure Gold!",
    ) { d, t -> d.strip(listOf(redLapis, null, greenLapis, null), List(4) { false }, t, mapOf(1 to true, 3 to true)) },
)

@Composable
private fun HowToPlayPanel(vm: GameViewModel) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    val p = helpPages[page]
    GamePanel("How to Play", vm::pop) {
        Text(p.title, style = bodyStyle(20.sp, Palette.goldLight, bold = true))
        BoxWithConstraints(Modifier.fillMaxWidth().height(96.dp)) {
            Canvas(Modifier.fillMaxSize()) { p.art(this, vm.fx.now) }
        }
        Text(p.text, style = bodyStyle(15.sp), textAlign = TextAlign.Center)
        Text("${page + 1} / ${helpPages.size}", style = bodyStyle(12.sp, Palette.parchment.copy(alpha = 0.6f)))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BrassButton("Back", { vm.click(); page-- }, Modifier.weight(1f), enabled = page > 0, dark = true)
            if (page < helpPages.lastIndex) BrassButton("Next", { vm.click(); page++ }, Modifier.weight(1f))
            else BrassButton("Got it!", vm::pop, Modifier.weight(1f))
        }
    }
}
