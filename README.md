## What is Triutilizer?


Triutilizer is a library mod that provides a powerful multithreading system for other mods to use. It allows CPU-intensive tasks to run in parallel on multicore servers while keeping all game state access safely on the main thread.


**This is a library mod** - you typically only need to install it if another mod requires it as a dependency.


## Key Features


✅ **Priority-based task scheduling** - Critical tasks execute first  
✅ **Thread-safe design** - Automatic synchronization prevents crashes  
✅ **Zero vanilla interference** - Doesn't modify tick loop or chunk loading  
✅ **Battle-tested** - Deployed on multiple live servers  
✅ **Comprehensive API** - Easy integration for mod developers


## How It Works


Triutilizer creates a pool of worker threads that handle computationally expensive operations in parallel. When tasks need to interact with the Minecraft world (blocks, entities, etc.), they automatically execute on the main thread to ensure safety.


**Technical Details:**
- Uses `ThreadPoolExecutor` with `PriorityBlockingQueue`
- Priority levels: CRITICAL > HIGH > NORMAL > LOW
- Tasks return as `CompletableFuture`s for async handling
- Worker count: CPU cores - 1 (configurable)


## For Mod Developers


Triutilizer is the easiest way to add multithreading to your mods to boost performance (see github for documentation).


**Developer**: Tribulla  
Core code written by human developers, with AI assistance for documentation and formatting.