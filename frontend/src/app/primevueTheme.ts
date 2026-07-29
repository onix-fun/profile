import { definePreset } from "@primeuix/themes";
import Aura from "@primeuix/themes/aura";
import { themes } from "@onix/design-system";

const light = themes.light;
const dark = themes.dark;

export const ProfileTheme = definePreset(Aura, {
  semantic: {
    primary: { 50: light.tone.neutral.soft, 100: light.tone.neutral.soft, 200: light.surfaceActive, 300: light.surfaceStrong, 400: light.textMuted, 500: light.tone.neutral.solid, 600: light.tone.neutral.ink, 700: light.tone.neutral.ink, 800: light.text, 900: light.text, 950: light.text },
    colorScheme: {
      light: { surface: { 0: light.surfaceBase, 50: light.surfacePage, 100: light.surfaceMuted, 200: light.surfaceSoft, 300: light.surfaceActive, 400: light.textSubtle, 500: light.textSubtle, 600: light.textMuted, 700: light.tone.neutral.solid, 800: light.text, 900: light.text, 950: light.text } },
      dark: { surface: { 0: light.surfaceBase, 50: dark.surfacePage, 100: dark.surfaceMuted, 200: dark.surfaceBase, 300: dark.surfaceActive, 400: dark.textSubtle, 500: dark.textSubtle, 600: dark.textMuted, 700: dark.text, 800: dark.text, 900: dark.text, 950: light.surfaceBase } },
    },
  },
});
