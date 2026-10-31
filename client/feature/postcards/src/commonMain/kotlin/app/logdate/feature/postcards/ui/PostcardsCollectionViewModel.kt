package app.logdate.feature.postcards.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.logdate.client.repository.postcards.Postcard
import app.logdate.client.repository.postcards.PostcardRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the Postcards collection screen.
 *
 * Provides an observable list of all saved Postcards, sorted by most recently modified.
 */
class PostcardsCollectionViewModel(
    postcardRepository: PostcardRepository,
) : ViewModel() {
    val postcards: StateFlow<List<Postcard>> =
        postcardRepository
            .observeAllPostcards()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )
}
