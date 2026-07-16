import { describe, expect, it } from "vitest";
import {
  buildCreatePostInput,
  emptyPostEditorState,
  hasPublishablePostContent,
} from "@/features/editor/postEditor";

describe("post publishability", () => {
  it("does not treat a title, a divider, or a media reference as publishable content", () => {
    const titleOnly = emptyPostEditorState();
    titleOnly.title = "Только заголовок";

    const dividerOnly = emptyPostEditorState();
    dividerOnly.markdown = ":::onix DIVIDER {}";

    const mediaReferenceOnly = emptyPostEditorState();
    mediaReferenceOnly.markdown = "![[media:missing|ghost.png]]";

    expect(hasPublishablePostContent(titleOnly)).toBe(false);
    expect(hasPublishablePostContent(dividerOnly)).toBe(false);
    expect(hasPublishablePostContent(mediaReferenceOnly)).toBe(false);
    expect(() => buildCreatePostInput(dividerOnly)).toThrow(/содержательный блок/i);
  });

  it("accepts a complete special block or a real attachment", () => {
    const poll = emptyPostEditorState();
    poll.markdown = ':::onix POLL {"question":"Куда летим?","options":[{"id":"a","label":"Марс"},{"id":"b","label":"Луна"}]}' ;

    const file = emptyPostEditorState();
    file.attachments = [{
      id: "ready-file",
      blobId: "blob-1",
      url: "/content-media/blob-1",
      name: "hidden-name.pdf",
      mimeType: "application/pdf",
      size: 3,
      type: "FILE",
    }];

    expect(hasPublishablePostContent(poll)).toBe(true);
    expect(hasPublishablePostContent(file)).toBe(true);
  });
});
