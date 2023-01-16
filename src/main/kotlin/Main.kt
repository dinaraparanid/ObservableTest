import io.reactivex.rxjava3.core.*
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.Optional
import java.util.concurrent.Executors
import kotlin.system.exitProcess

private fun getTotalFilesNumber(file: File, filesFound: Long = 0L): Long = when {
    file.isFile -> filesFound + 1

    else -> file
        .listFiles()
        ?.fold(filesFound) { acc, child -> getTotalFilesNumber(child, acc) }
        ?: 0
}

// ######################################## RxJava ##################################################

private fun Emitter<Optional<File>>.searchAndSendFiles(fileName: String, file: File) {
    when {
        file.isFile -> onNext(
            when (fileName) {
                in file.name -> Optional.of(file)
                else -> Optional.empty()
            }
        )

        else -> file
            .listFiles()
            ?.map { childFile -> searchAndSendFiles(fileName, childFile) }
    }
}

private fun SingleEmitter<Pair<List<File>, Long>>.searchAndSendFiles(fileName: String, rootFile: File) {
    val foundFiles = mutableListOf<File>()
    val files = ArrayDeque(listOf(rootFile))
    var totalFilesNumber = 0L

    while (files.isNotEmpty()) {
        val file = files.removeFirst()

        when {
            file.isFile -> {
                totalFilesNumber++

                if (fileName in file.name)
                    foundFiles.add(file)
            }

            else -> file.listFiles()?.let(files::addAll)
        }
    }

    onSuccess(foundFiles to totalFilesNumber)
}

// ######################################## Coroutines ##################################################

private suspend fun Channel<File?>.searchAndSendFiles(
    fileName: String,
    file: File,
): Unit = coroutineScope {
    when {
        file.isFile -> send(
            when (fileName) {
                in file.name -> file
                else -> null
            }
        )

        else -> file
            .listFiles()
            ?.map { childFile -> searchAndSendFiles(fileName, childFile) }
    }
}

private suspend fun FlowCollector<File?>.searchAndSendFiles(fileName: String, file: File): Unit = coroutineScope {
    when {
        file.isFile -> emit(
            when (fileName) {
                in file.name -> file
                else -> null
            }
        )

        else -> file
            .listFiles()
            ?.forEach { childFile -> searchAndSendFiles(fileName, childFile) }
    }
}

private suspend fun MutableSharedFlow<File?>.searchAndSendFiles(
    fileName: String,
    file: File,
): Unit = coroutineScope {
    when {
        file.isFile -> emit(
            when (fileName) {
                in file.name -> file
                else -> null
            }
        )

        else -> file
            .listFiles()
            ?.map { childFile -> searchAndSendFiles(fileName, childFile) }
    }
}

private suspend fun ProducerScope<File?>.searchAndSendFiles(
    fileName: String,
    file: File,
): Unit = coroutineScope {
    when {
        file.isFile -> send(
            when (fileName) {
                in file.name -> file
                else -> null
            }
        )

        else -> file
            .listFiles()
            ?.map { childFile -> searchAndSendFiles(fileName, childFile) }
    }
}

// ######################################## TESTING ##################################################

private infix fun Long.percentageFrom(total: Long) = this * 100.0 / total

@OptIn(ObsoleteCoroutinesApi::class)
fun main() = runBlocking {
    print("File name: ")
    val fileName = readln()

    print("Directory to start searching: ")
    val startFile = File(readln())

    val totalFilesNumberTask = async(Dispatchers.Default) {
        getTotalFilesNumber(startFile)
    }

    val executor = Executors.newSingleThreadExecutor()
    val coroutineContext = executor.asCoroutineDispatcher()

    print("Debug files? (Y or N, default N) ")
    val printFiles = readln().trim().let { it == "Y" }

    val totalFilesNumber = totalFilesNumberTask.await()

    // ######################################## RxJava ##################################################

    // -------------------------------------- Observable -----------------------------------------------

    fun testObservable() {
        val start = System.currentTimeMillis()

        val fileObservable = Observable.create { subscriber ->
            subscriber.searchAndSendFiles(
                fileName = fileName,
                file = startFile,
            )

            subscriber.onComplete()
        }

        var filesFound = 0L
        var totalFileCounter = 0L

        fileObservable
            .subscribeOn(Schedulers.from(executor))
            .blockingSubscribe { file ->
                totalFileCounter++

                if (file.isPresent) {
                    filesFound++
                    if (printFiles) println(file.get())
                }

                if (totalFileCounter == totalFilesNumber) {
                    println("\nTotal time for Observable: ${System.currentTimeMillis() - start} ms")
                    println("Files found: $filesFound, total: $totalFileCounter; it's just ${String.format("%.3f", filesFound percentageFrom totalFileCounter)}% of whole directory")
                    exitProcess(0)
                }
            }
    }

    // -------------------------------------- Flowable -----------------------------------------------

    fun testFlowable() {
        val start = System.currentTimeMillis()

        val fileFlowable = Flowable.create(
            { subscriber ->
                subscriber.searchAndSendFiles(
                    fileName = fileName,
                    file = startFile,
                )

                subscriber.onComplete()
            },
            BackpressureStrategy.BUFFER
        )

        var filesFound = 0L
        var totalFileCounter = 0L

        fileFlowable
            .subscribeOn(Schedulers.from(executor))
            .subscribe { file ->
                totalFileCounter++

                if (file.isPresent) {
                    filesFound++
                    if (printFiles) println(file.get())
                }

                if (totalFileCounter == totalFilesNumber) {
                    println("\nTotal time for Flowable: ${System.currentTimeMillis() - start} ms")
                    println("Files found: $filesFound, total: $totalFileCounter; it's just ${String.format("%.3f", filesFound percentageFrom totalFileCounter)}% of whole directory")
                    exitProcess(0)
                }
            }
    }

    // -------------------------------------- Single -----------------------------------------------

    fun testSingle() {
        val start = System.currentTimeMillis()

        val fileSingle = Single.create { subscriber ->
            subscriber.searchAndSendFiles(
                fileName = fileName,
                rootFile = startFile,
            )
        }

        fileSingle
            .subscribeOn(Schedulers.from(executor))
            .blockingSubscribe { (files, totalFileCounter) ->
                println("\nTotal time for Single: ${System.currentTimeMillis() - start} ms")
                println("Files found: ${files.size}, total: $totalFileCounter; it's just ${String.format("%.3f", files.size.toLong() percentageFrom totalFileCounter)}% of whole directory")
                exitProcess(0)
            }
    }

    // ######################################## Coroutines ##################################################

    // -------------------------------------- Channel -----------------------------------------------

    suspend fun testChannel() {
        val start = System.currentTimeMillis()

        val fileChannel = Channel<File?>().apply {
            launch(coroutineContext) {
                searchAndSendFiles(
                    fileName = fileName,
                    file = startFile,
                )

                close()
            }
        }

        var filesFound = 0L
        var totalFileCounter = 0L

        fileChannel.consumeEach { file ->
            totalFileCounter++

            file?.let {
                filesFound++
                if (printFiles) println(it)
            }
        }

        println("\nTotal time for Channel: ${System.currentTimeMillis() - start} ms")
        println("Files found: $filesFound, total: $totalFileCounter; it's just ${String.format("%.3f", filesFound percentageFrom totalFileCounter)}% of whole directory")
        exitProcess(0)
    }

    // -------------------------------------- Flow -----------------------------------------------

    suspend fun testFlow() {
        val start = System.currentTimeMillis()

        val fileFlow = flow {
            searchAndSendFiles(
                fileName = fileName,
                file = startFile,
            )
        }

        var filesFound = 0L
        var totalFileCounter = 0L

        fileFlow.collect { file ->
            totalFileCounter++

            file?.let {
                filesFound++
                if (printFiles) println(it)
            }
        }

        println("\nTotal time for Flow: ${System.currentTimeMillis() - start} ms")
        println("Files found: $filesFound, total: $totalFileCounter it's just ${String.format("%.3f", filesFound percentageFrom totalFileCounter)}% of whole directory")
        exitProcess(0)
    }

    // -------------------------------------- Shared Flow -----------------------------------------------

    suspend fun testSharedFlow() {
        val start = System.currentTimeMillis()

        val fileSharedFlow = MutableSharedFlow<File?>().apply {
            launch(coroutineContext) {
                searchAndSendFiles(
                    fileName = fileName,
                    file = startFile,
                )
            }
        }

        var filesFound = 0L
        var totalFileCounter = 0L

        fileSharedFlow.collect { file ->
            totalFileCounter++

            file?.let {
                filesFound++
                if (printFiles) println(it)
            }

            if (totalFileCounter == totalFilesNumber) {
                println("\nTotal time for Shared Flow: ${System.currentTimeMillis() - start} ms")
                println("Files found: $filesFound, total: $totalFileCounter; it's just ${String.format("%.3f", filesFound percentageFrom totalFileCounter)}% of whole directory")
                exitProcess(0)
            }
        }
    }

    // -------------------------------------- Broadcast Channel (Deprecated) -----------------------------------------------

    suspend fun testBroadcastChannel() {
        val start = System.currentTimeMillis()

        val fileBroadcastChannel = broadcast {
            launch(coroutineContext) {
                searchAndSendFiles(
                    fileName = fileName,
                    file = startFile,
                )

                close()
            }
        }

        var filesFound = 0L
        var totalFileCounter = 0L

        fileBroadcastChannel.consumeEach { file ->
            totalFileCounter++

            file?.let {
                filesFound++
                if (printFiles) println(it)
            }
        }

        println("\nTotal time for Broadcast Channel: ${System.currentTimeMillis() - start} ms")
        println("Files found: $filesFound, total: $totalFileCounter; it's just ${String.format("%.3f", filesFound percentageFrom totalFileCounter)}% of whole directory")
        exitProcess(0)
    }

    // -------------------------------------- Callback Flow -----------------------------------------------

    suspend fun testCallbackFlow() {
        val start = System.currentTimeMillis()

        val fileFlow = callbackFlow {
            launch(coroutineContext) {
                searchAndSendFiles(
                    fileName = fileName,
                    file = startFile,
                )
            }

            awaitClose { channel.close() }
        }

        var filesFound = 0L
        var totalFileCounter = 0L

        fileFlow.collect { file ->
            totalFileCounter++

            file?.let {
                filesFound++
                if (printFiles) println(it)
            }

            if (totalFileCounter == totalFilesNumber) {
                println("\nTotal time for Callback Flow: ${System.currentTimeMillis() - start} ms")
                println("Files found: $filesFound, total: $totalFileCounter; it's just ${String.format("%.3f", filesFound percentageFrom totalFileCounter)}% of whole directory")
                exitProcess(0)
            }
        }
    }

    println("Choose library:")
    println("1. RxJava")
    println("2. Kotlin Coroutines\n")
    print("Print number: ")

    when (readln().toInt()) {
        1 -> {
            println("Choose observer:")
            println("1. Observable")
            println("2. Flowable")
            println("3. Single\n")
            print("Print number: ")

            when (readln().toInt()) {
                1 -> testObservable()
                2 -> testFlowable()
                3 -> testSingle()
                else -> println("Unknown observer")
            }
        }

        2 -> {
            println("Choose observer:")
            println("1. Channel")
            println("2. Flow")
            println("3. Shared Flow")
            println("4. Broadcast Channel (Deprecated)")
            println("5. Callback Flow\n")
            print("Print number: ")

            when (readln().toInt()) {
                1 -> testChannel()
                2 -> testFlow()
                3 -> testSharedFlow()
                4 -> testBroadcastChannel()
                5 -> testCallbackFlow()
                else -> println("Unknown observer")
            }
        }

        3 -> println("Unknown library")
    }
}