# Hosting Mouna Lab

The Lab is a static site: no server, no database, no API. Both hosts serve `lab/dist` with the same security
headers ([lab/headers.json](../lab/headers.json)). The CSP sets `connect-src 'self'`, so the page cannot send
anything to any other server, which is the privacy claim made checkable. The optional nurse-call link (Link tab) is a
direct WebRTC connection between two devices on the same network, with no ICE servers and no server in between. Camera access needs HTTPS, which both hosts provide.

The repository can stay private: both hosts deploy private GitHub repos.

## Vercel (recommended)

Dashboard, about 2 minutes:
1. vercel.com → Add New → Project → Import `vhmaadhav/mouna-spike` (grant the Vercel GitHub app access to it).
2. **Root Directory: `lab`**. Framework is detected as Vite; `lab/vercel.json` sets the build, output and headers.
3. Deploy. Every push to `main` redeploys.

Or the CLI:
```bash
npx vercel login
npx vercel --cwd lab --prod
```

## Render

1. dashboard.render.com → New → Blueprint → connect `vhmaadhav/mouna-spike`.
2. Render reads [render.yaml](../render.yaml): a static site from `lab/`, published from `lab/dist`, with the same headers.

## Check after deploying

- Open the URL, start the camera, teach a phrase, mouth it in Speak.
- DevTools → Network: after the first load, nothing is requested while you use it.
- DevTools → Console: no CSP errors.
- `curl -sI <url> | grep -i content-security-policy` shows the policy.

## The lip encoder on the hosted site

By default the hosted Lab runs the lip-geometry recogniser only: the encoder model (86 MB, LipLearner weights) is
not in the repository, so the build has nothing to copy and the Speak tab hides the Recogniser switch. To host the
encoder too, commit `lab/public/models/encoder.int8.onnx` via Git LFS or a release asset, after confirming the
LipLearner weights may be redistributed (pre-trained on LRW). Locally, `MOUNA_NO_ENCODER=1 npm run build`
reproduces exactly what Vercel serves.

## What judges see

The consent screen, then the Teach tab. Everything stays in their browser (IndexedDB); the Data tab deletes it.
