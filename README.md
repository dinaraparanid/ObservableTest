# Observable Test

Next code is the benchmark of all suitable (for the task) observables from both RxJava and Coroutines.
Task is to find all files in given directory (including files in subdirectories) with given "fileName"
in their name (including extension). For all observables, except Single and Flow, I have sent each file
separately from background thread (producer) to main thread (collector). For Single, I have collected all
files in list. For Flow, - everything is the same as with others, but file searching is done in main thread.
Also, I only used one background thread (where it is possible) for file walking

After 10 tries of searching files in folder with 65K files in total (I was searching for all '.xml' files and found 930 of them), I have received next results for each observer:

## RxJava

<ul>
    <li>Observable: 468 ms</li>
    <li>Flowable: 450.6 ms</li>
    <li>Single: 463 ms</li>
</ul>


## Coroutines

<ul>
    <li>Channel: 777,8 ms </li>
    <li>Flow: 397,9 ms</li>
    <li>Shared Flow: 1405 ms</li>
    <li>Broadcast Channel: 623.2 ms</li>
    <li>Callback Flow: 503,3 ms</li>
</ul>

### Fun facts:

<ul>
    <li>Surprisingly, BroadcastChannel (that is deprecated now) is far better than its 'successor' SharedFlow.</li>
    <li>All observables from RxJava are mostly on same level of performance, however, Flowable is usually faster.</li>
    <li>Callback Flow is very close to RxJava's observables.</li>
    <li>Flow looks better, because it is suited for a single thread (no synchronization).</li>
</ul>
