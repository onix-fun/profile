export type ThemeName = "light" | "dark";
export type Tone = "neutral" | "info" | "success" | "warning" | "danger" | "pink" | "cyan";

export interface ToneVars {
  color: string;
  background: string;
}

export declare const tokens: {
  font: {
    family: string;
    size: Record<"caption" | "label" | "control" | "body" | "title", string>;
    weight: Record<"regular" | "medium" | "semibold" | "bold" | "strong" | "title", number>;
    lineHeight: Record<"caption" | "label" | "body" | "title", number>;
  };
  spacing: Record<"0" | "1" | "2" | "3" | "4" | "5" | "6" | "8" | "10" | "12", string>;
  radii: Record<"xs" | "sm" | "md" | "lg" | "xl" | "pill", string>;
  motion: Record<"fast" | "base", string>;
};

export declare const themes: Record<ThemeName, Record<string, string>>;
export declare const semanticColors: Record<string, string>;
export declare const platforms: Record<string, { label: string; color: string }>;
export declare const spacing: typeof tokens.spacing;
export declare const typography: typeof tokens.font;
export declare const radii: typeof tokens.radii;
export declare const motion: typeof tokens.motion;
export declare function createClassName(...parts: Array<string | false | null | undefined>): string;
export declare function getToneVars(tone: Tone): ToneVars;
