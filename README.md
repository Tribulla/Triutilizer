# Triutilizer
this is a mod is for servers that reworks how minecraft uses CPU resources to improve performance on multicore servers.

NB: this mod is made to run ONLY ON SERVERS and not on client so DO NOT run it on client because it has not been tested and might cause file corruption. if you use the stats command and see that the entity que is full and skiping tasks then dont panic because it is normal and actually improves performance (I have no clue how).

info for nerds:
this mod works by applying a manager aproach to the task manager which means that there is one manager thread that takes a task from the que and gives it to a worker thread to complete after which the worker thread completes the task and sends it back to the main thread to be loaded.
this approach makes sure that the server isnt at half resource usage while at 8tps.

feel free to suport me on patreon to also get exclusive benefts: https://www.patreon.com/tribulla

(the code was written by human but then formatted by AI to add comments and make the code look good)
