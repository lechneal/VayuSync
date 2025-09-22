
import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class SelectionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val PREFS_NAME = "SelectionPrefs"
        private const val KEY_SELECTION_HISTORY = "selectionHistoryJson"
        private const val KEY_CURRENT_HISTORY_INDEX = "currentHistoryIndex"
        private const val MAX_SELECTION_HISTORY_SIZE = 50
        private val GSON = Gson()
    }

    private val sharedPreferences = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Internal LiveData for history and index
    private val _selectionHistoryInternal = MutableLiveData<List<Set<String>>>()
    private val _currentHistoryIndexInternal = MutableLiveData<Int>()

    // Public LiveData for the current selection (derived)
    private val _currentlySelectedUris = MutableLiveData<Set<String>>()
    val currentlySelectedUris: LiveData<Set<String>> = _currentlySelectedUris

    init {
        Log.d("SelectionViewModel", "Initializing and loading from SharedPreferences.")
        loadStateFromPreferences()
        updateDerivedLiveData() // Initialize derived LiveData
    }

    private fun loadStateFromPreferences() {
        val historyJson = sharedPreferences.getString(KEY_SELECTION_HISTORY, null)
        val loadedHistory: List<Set<String>> = if (historyJson != null) {
            try {
                // Ensure we are deserializing a List of Lists of Strings for Gson
                val type = object : TypeToken<ArrayList<List<String>>>() {}.type
                val listOfLists: ArrayList<List<String>> = GSON.fromJson(historyJson, type)
                listOfLists.map { it.toSet() } // Convert inner lists to sets
            } catch (e: Exception) {
                Log.e("SelectionViewModel", "Error deserializing history from JSON", e)
                listOf()
            }
        } else {
            listOf()
        }
        _selectionHistoryInternal.value = loadedHistory

        val loadedIndex = sharedPreferences.getInt(KEY_CURRENT_HISTORY_INDEX, -1)
        _currentHistoryIndexInternal.value = loadedIndex

        Log.d("SelectionViewModel", "Loaded history size: ${loadedHistory.size}, index: $loadedIndex")
    }

    private fun saveStateToPreferences() {
        val currentHistory = _selectionHistoryInternal.value ?: listOf()
        val currentIndex = _currentHistoryIndexInternal.value ?: -1

        Log.d("SelectionViewModel", "Saving to SharedPreferences. History size: ${currentHistory.size}, index: $currentIndex")

        // Convert List<Set<String>> to List<List<String>> for easier Gson serialization
        val historyToSave = currentHistory.map { it.toList() }
        val historyJson = GSON.toJson(historyToSave)

        sharedPreferences.edit {
            putString(KEY_SELECTION_HISTORY, historyJson)
            putInt(KEY_CURRENT_HISTORY_INDEX, currentIndex)
        }
    }

    private fun updateDerivedLiveData() {
        val history = _selectionHistoryInternal.value ?: listOf()
        val index = _currentHistoryIndexInternal.value ?: -1

        if (index != -1 && history.isNotEmpty() && index < history.size) {
            _currentlySelectedUris.value = history[index]
        } else {
            _currentlySelectedUris.value = setOf() // Default to empty if no valid state
        }
        Log.d("SelectionViewModel", "Updated derived LiveData. Current Selection: ${_currentlySelectedUris.value?.size}")
    }

    fun recordSelectionChange(newSelection: Set<Uri>) {
        val newSelectionStrings = newSelection.map { it.toString() }.toSet()
        val currentHistory = _selectionHistoryInternal.value?.toMutableList() ?: mutableListOf()
        var currentIndex = _currentHistoryIndexInternal.value ?: -1

        // If current selection is same as new selection, and it's already the latest in history, do nothing.
        // This check needs to use the derived current selection.
        val derivedCurrentSelection = if (currentIndex != -1 && currentIndex < currentHistory.size) currentHistory[currentIndex] else null
        if (derivedCurrentSelection == newSelectionStrings && currentIndex == currentHistory.lastIndex) {
            Log.d("SelectionViewModel", "Skipping identical selection state. Size: ${newSelectionStrings.size}")
            if (_currentlySelectedUris.value != newSelectionStrings) {
                _currentlySelectedUris.value = newSelectionStrings
            }
            return
        }

        if (currentIndex < currentHistory.lastIndex) {
            currentHistory.subList(currentIndex + 1, currentHistory.size).clear()
        }

        if (currentHistory.isEmpty() || currentHistory.lastOrNull() != newSelectionStrings) {
            currentHistory.add(newSelectionStrings)
        }

        while (currentHistory.size > MAX_SELECTION_HISTORY_SIZE) {
            currentHistory.removeAt(0)
        }

        _selectionHistoryInternal.value = currentHistory
        _currentHistoryIndexInternal.value = if (currentHistory.isEmpty()) -1 else currentHistory.lastIndex

        updateDerivedLiveData()
        saveStateToPreferences()
    }

    fun undoSelection() {
        var currentIndex = _currentHistoryIndexInternal.value ?: -1

        if (currentIndex > 0) {
            currentIndex--
            _currentHistoryIndexInternal.value = currentIndex
            updateDerivedLiveData()
            saveStateToPreferences()
        }
    }

    fun redoSelection() {
        var currentIndex = _currentHistoryIndexInternal.value ?: -1
        val history = _selectionHistoryInternal.value ?: return

        if (currentIndex < history.lastIndex) {
            currentIndex++
            _currentHistoryIndexInternal.value = currentIndex
            updateDerivedLiveData()
            saveStateToPreferences()
        }
    }

    fun clearAll() {
        _selectionHistoryInternal.value = listOf()
        _currentHistoryIndexInternal.value = -1
        updateDerivedLiveData()
        saveStateToPreferences()
    }

    fun canUndo(): Boolean = _currentHistoryIndexInternal.value ?: -1 > 0
    fun canRedo(): Boolean = (_currentHistoryIndexInternal.value ?: -1) < (_selectionHistoryInternal.value?.lastIndex ?: -1)

    fun isSelectionActive(): Boolean = _currentlySelectedUris.value?.isNotEmpty() ?: false
    fun getCurrentlySelectedUris(): Collection<Uri> {
        return _currentlySelectedUris.value?.map { it.toUri() } ?: emptySet()
    }

    fun toggleAndRecordSelection(currentUri: Uri) {
        val currentSelectedSet = getCurrentlySelectedUris().toMutableSet()

        if (currentSelectedSet.contains(currentUri)) {
            currentSelectedSet.remove(currentUri)
        } else {
            currentSelectedSet.add(currentUri)
        }
        recordSelectionChange(currentSelectedSet)
    }
}
