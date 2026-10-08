# DeviceLink design system

`core/designsystem` owns the app's visual configuration. `feature/link` describes
user journeys using semantic Material roles and shared components; it does not
define colors, spacing, typography or corner radii.

## Customization without screen changes

Open **Settings → Make it yours**. These options apply immediately and persist
across app restarts:

- Theme: system, light or dark.
- Accent: Ocean, Iris or Forest.
- Wallpaper colors on Android 12 and later. This replaces the built-in accent
  palette while enabled.
- Corners: soft, round or square. These configure Material shape tokens used by
  panels, dialogs and fields. Some Material controls, such as switches and
  standard pill buttons, retain their semantic component shape.

The same settings store holds a preferred session length of 5, 15 or 30 minutes.
Changing it affects the next session, not an active session's deadline.

`AppearanceStore(context)` persists the validated `Appearance` model using
private app preferences and exposes `appearance: StateFlow<Appearance>`.
`update(Appearance)` is the supported customization entry point. Enum values
and session duration are validated when loaded; unrecognized settings fall back
to safe defaults. There is no remote theme download or arbitrary JSON import in
this version.

```kotlin
val appearance by appearanceStore.appearance.collectAsState()
DeviceTheme(appearance) {
    LinkPanel {
        SectionHeading(title = stringResource(R.string.example_title))
        // Use MaterialTheme.typography and MaterialTheme.colorScheme roles.
    }
}
```

The application composition root supplies `AppearanceStore` to `DeviceLinkApp`.
It can provide a different saved configuration without changing feature screens.

## Developer extension points

- `DeviceTheme.kt`: light/dark palettes, typography, semantic shape scales and
  `DeviceTokens`. Add a palette here and an `Accent` value in `AppearanceStore.kt`
  to introduce another built-in accent. The settings label belongs in feature
  string resources.
- `LocalDeviceTokens`: spacing, layout constraints, device marks and QR sizing.
  Use token names rather than new `dp`/`sp` literals in feature code.
- `Components.kt`: `LinkPanel`, `SectionHeading`, `DeviceMark`, `DeviceIdentity`
  and `QuietMessage`. Change shared component construction here to update every
  usage; screen logic stays separate.
- `AppearanceStore.kt`: persisted preferences and validation. Extend this model
  deliberately when exposing additional customization to users.

Visual architecture does not mean that every text or business interaction is a
design token. User-facing language belongs in Android string resources; transfer
consent, connection lifecycle and commands belong in their feature/domain layers.

## Layout and accessibility

Screens respect system insets and scroll vertically. Content is centered with a
720 dp maximum width; actions and settings chips wrap through `FlowRow`. Text
uses scalable Material typography, avoids fixed-height containers and supports
RTL layout through start/end-aware Compose layouts. Decorative device marks have
no redundant accessibility description. Session and wallpaper-color switches
are named; section titles expose heading semantics. QR and code verification
dialog bodies scroll for landscape and large font settings.

QR codes always use the design system's opaque white background and black ink,
independently of the selected theme, to preserve scanner contrast.

The app never uses color as the only transfer-state indicator. Transfer labels,
progress and explicit Receive/Decline/Cancel actions express state in text.
Appearance controls expose selected-state semantics through Material chips.

## Verification boundaries

Compile and Android lint validate resources and API usage. Device QA checks the
real layout, large-font behavior, appearance persistence and the critical
send/receive callbacks. No coverage percentage or tests that merely repeat visual
constants are required. Radio transport and file correctness are protected in
their own layers, not by screenshot assertions.
