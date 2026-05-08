Using Go
See how to handle video files
how to modify and change video files
use FFMPEG-go library, wrapper around ffmpeg

## NEXT STEP

Focus on DB
Learn the database, look at models, what to store in db, write the functiosn for
queries.
Add a convert to GIF option

### Workflow of the project

- User uploads file via curl or frontend (Use signed upload url, some storage
  like S3, R2)
- Get the metadata of the video
- Apply restrictions(150MB, 5Min, 1080p)
- if alright, store some data in db. create status(uploaded, processing,
  pending, complete)
- send back ok response the API that uploaded the video
- based on entries in db, or any queue, call background worker, to apply the
  video edit options (Speed, Volume, Trim, Audio, Flip, GIF), this happens in
  the background
- use socket or something to show progress to user, for ffmpeg process that's
  running
- After job is finished, upload the result, and get user to downlaod the file
- Remove DB entry and file from storage after maybe 1 hour.

### Parts

working api

- /upload
- /video/trim
- /video/volume
- ...
- /getVideo

The ffmpeg worker to handle background task
Some queue to issue tasks to workers, check new request etc
Database, sqlite should work fine, doesn't need much
A storage provider, like S3, R2
Hosting on Fly.io, or render
Frontend maybe in react
User system for auth
Delete temp files on the server, the finished result should upload to storage pool
Background Processes
Progress tracking
Actual UI to apply the options, might need a form slider for trimming
Look into indexes for processing statuses and CreatedAt entry for cleanup

LIMITS
150MB max
1080p max
5 min max
1 active job per IP
auto-delete after 24h

FROM GPT

1. HTTP server
2. Routing
3. Request validation
4. Auth/session handling
5. Database layer
6. Storage layer
7. Processing layer
8. Background jobs
9. State tracking
10. Cleanup system
11. Error handling
12. Logging
13. Configuration
14. Rate limiting

### Need to implement:

- Increase/decrease video speed
- Increase/decrase volume
- Rotate video
- Flip video
- change format(maybe)
- Trim
- Extract Audio

Make it a cli tool for now
Might miake it into an API, with UI

### For API

- Using external service for file upload
- Use React for UI
- Create a user system, so API doesn't get spammed
  - Also useful for learning authorization and authenticaion

### Start working on it.

Need a DB, sqlite might work file

```
object video{
  ID
  inputPath
  outputPath
  Status
  CreatedAt
}
```

need to get the pipeline working, at local level first

- upload
- run ffmpeg
- update DB
- output
- downlaod

basic api at first

- post /upload
- post /trim
- get /video
