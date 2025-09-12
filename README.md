# Triutilizer

I just found out the production version of the mod does not work properly so currently the mod does nothing.
(thank you very much JTK222 | Lukas for letting me know).

Triutilizer adds a prioritized worker pool to run CPU-heavy tasks in parallel on multicore servers. It keeps world access on the main thread and only parallelizes pure computation, helping compatible features finish faster.

Info for nerds:
Uses a fixed-size ThreadPoolExecutor with a PriorityBlockingQueue; higher-priority tasks run first.
Tasks complete as CompletableFutures; any game-interacting completion runs on the main thread.
Utilities: mapParallel, mapChunked, cancellable tasks, and range-parallel loops for CPU-bound work.
Does not alter the vanilla tick loop, chunk loading, or Forge’s scheduler.

servers this mod has been deployed in and monitored by the developer:
VSSMP - ended
TDI (the divided isles) - https://discord.gg/P6VTrethrk
CCW (create constant warfare) - https://discord.gg/wgFHd3VaNx

feel free to suport me on patreon to also get exclusive benefts: https://www.patreon.com/tribulla

(the code was written by human but then formatted by AI to add comments and make the code look good)
