# Media v4: source verification, processing and stable delivery

Media v2 treats an uploaded source and a processing run as independent
resources. Completing a browser upload only starts source verification. It
does not create delivery variants. A consumer starts a neutral, versioned
pipeline explicitly with `RequestProcessing`.

The supported browser source policy is `browser-native-v1`:

- images: JPEG, PNG and WebP;
- video: MP4 with H.264 and optional AAC;
- audio: MP3 or M4A/AAC.

Interactive capture surfaces may explicitly use `browser-capture-v1`. It has
the same image/audio limits and additionally accepts WebM video produced by
browser `MediaRecorder` (VP8, VP9 or AV1). This is still a generic Media
policy: the service does not know that Content currently uses it for stories.
The requested delivery pipeline remains `video-web-1080-v1`, which produces a
portable H.264/AAC MP4 and poster.

The declared MIME type is only an early rejection hint. Media hashes the
uploaded bytes, detects their MIME type and verifies video/audio codecs before
marking the source `AVAILABLE`.

Content uses the following pipelines only after a publication request:

- `image-responsive-web-v1`;
- `video-web-1080-v1`;
- `audio-web-v1`.

Content stores `assetId`, processing `generation`, variant metadata and the
`STABLE_V2` delivery contract. It never stores a presigned download URL. Public
responses contain stable relative routes such as:

```text
/content-media/assets/{assetId}/{generation}/{variantName}
```

On every request Content checks draft ownership or post visibility, calls
Media `ResolveSource`/`ResolveDelivery`, and returns a temporary redirect with
`Cache-Control: private, no-store`. An expired object-store signature is
therefore replaced on the next request to the same stable Content route.

The `media-blobs` bucket must remain private. Browser multipart uploads and
delivery redirects work through signed URLs; anonymous bucket access is not
part of the contract.

Media derives the v2 client namespace from the authenticated service identity
(mTLS in deployed environments). The `ownerRef` is opaque and is interpreted
only inside that service namespace. The local plaintext topology uses the
`x-onix-service` metadata fallback so development remains usable without
weakening the deployed identity boundary.

After Content atomically activates a publication, it calls `ReleaseSource` for
each ready generation. Media removes the original only when no other reference
requires the underlying blob. Failed originals remain retryable for seven days
and are then handled by the failed-source GC.
