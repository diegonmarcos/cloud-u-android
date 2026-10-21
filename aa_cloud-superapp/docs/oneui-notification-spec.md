# Samsung One UI notification panel - visual spec for the SuperApp Notify page (#1068)

Machine-checked: the `tokens` block below is compared with `NotifCard` (AggregatorNotify.kt) by `NotifStyleGuardTest`.
Change a value here and in `NotifCard` together, or the guard fails.

## Honest provenance

Samsung publishes no dp / sp / colour table for the One UI 7 / 8 notification panel. What follows is built from
(a) the AOSP / SystemUI values One UI is derived from (exact, cited), (b) what reviews and Samsung material state about
the One UI 7 / 8 panel (qualitative, cited), and (c) values observed on the owner's Galaxy A37 earlier in this ticket
(marked "observed"). Where a number is a judgement call it is marked as such. The on-phone check is still pending.

## Structure (One UI 7 / 8)

- One UI 7 split the shade: notifications are their own page (swipe down from the left), quick settings the other (right).
  Cards are larger with rounded corners, and the rounding extends to quick toggles. [1][2]
- Live notifications (One UI 7) show real-time state of an ongoing activity (timer, media, exercise, navigation) in the
  notification list, in the status bar chip and on the lock screen Now Bar; they are interactive. One UI 8 maps Android 16
  Live Updates onto the same surface for any app. [3][4]
- Notifications are GROUPED PER APP: one rounded container per app; when it holds several, the newest shows with a "+N"
  indicator and the group opens on tap; each item then shows its own time. Android 16 forces bundling by app. [5][6]
- Container header row: small monochrome app icon, app name, " - time", expand control at the end. [AOSP header: icon 18dp,
  app name + separator + time in the 12sp subtext style, expand button pill 24dp tall] [7]
- Below the cards, centred: "Notification settings" and "Clear" pills (One UI's clear-all). Here: "Mark all read" and "Archive all".
- Per-notification actions are pill buttons under the content (AOSP action button radius 18dp). [7]
- Dark mode: near-black panel; cards a lifted dark grey (observed #2A2A2E at ~92 %).

## AOSP values One UI inherits (exact) [7][8]

| item | value |
|---|---|
| notification_header_icon_size | 18dp |
| notification_subtext_size (header text, time) | 12sp |
| notification_text_size / title_text_size | 14sp |
| notification_big_title_text_size | 16sp |
| notification_2025_margin (content margin) | 16dp |
| notification_content_margin_end | 16dp |
| notification_action_button_radius | 18dp |
| notification_2025_left_icon_size (avatar) | 40dp |
| notification_expand_button_pill_height | 24dp |
| SystemUI notification_corner_radius | 28dp |
| SystemUI notification_shade_content_margin_horizontal | 16dp |
| SystemUI notification_section_header_height | 48dp |
| SystemUI notification_scrim_corner_radius | 32dp |
| notification_progress_segments_height | 6dp |

## Chosen One UI tokens (judgement where the sources are silent)

- Container: 28dp radius, 16dp content padding, 8dp gap between containers. Radius and padding are the AOSP/SystemUI numbers;
  the gap is observed.
- Header: 18dp tinted icon, 12sp app name (medium), 12sp time, same row, 8dp icon gap.
- Title 16sp semibold white; body 14sp, 2 lines folded, #B0B0B5; meta (time, secondary) 12sp #9A9AA0.
- Action pills: 36dp tall, 18dp radius, 14sp medium, fill #3A3A3F; four per box in one row: Mark read, Archive, Open, Reply.
- Live / ongoing card (the badges): same container, header (icon, name, state), a 26sp semibold headline value, 14sp
  supporting line, a 6dp rounded progress bar in the badge's accent over the pill colour, value tiles for multi-value badges.
- Avatar: 40dp round, right of the body.

```tokens
radius_dp = 28
pad_dp = 16
gap_dp = 8
header_icon_dp = 18
name_sp = 12
meta_sp = 12
title_sp = 16
body_sp = 14
action_height_dp = 36
action_radius_dp = 18
action_sp = 14
avatar_dp = 40
surface = #EB2A2A2E
title = #FFFFFFFF
body = #FFB0B0B5
meta = #FF9A9AA0
pill = #FF3A3A3F
```

## Sources

1. https://www.smartprix.com/bytes/one-ui-7-dont-miss-this-early-look-at-samsungs-big-update/
2. https://m.gsmarena.com/one_ui_7_design_changes_showcased_online-news-65094.php
3. https://androidsage.com/2025/02/19/samsung-one-ui-7-beta-program-is-complete/
4. https://www.chip.pl/2025/07/now-bar-w-one-ui-8-zacznie-wyswietlac-wiecej-informacji
5. https://9to5google.com/2025/09/04/samsung-galaxy-tablets-one-ui-8-notification-changes/
6. https://r2.community.samsung.com/t5/Galaxy-S/Ui8-android-16-features/m-p/20093522/highlight/true
7. https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/res/res/values/dimens.xml
8. https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/packages/SystemUI/res/values/dimens.xml
