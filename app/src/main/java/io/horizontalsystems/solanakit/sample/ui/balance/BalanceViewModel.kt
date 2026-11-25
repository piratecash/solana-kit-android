package io.horizontalsystems.solanakit.sample.ui.balance

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.horizontalsystems.solanakit.SolanaKit
import io.horizontalsystems.solanakit.sample.App
import kotlinx.coroutines.launch

class BalanceViewModel : ViewModel() {

    val receiveAddress = MutableLiveData<String>().apply { value = "" }
    val balance = MutableLiveData<String>().apply { value = "" }
    val balanceSyncState = MutableLiveData<String>().apply { value = "" }
    val tokenBalanceSyncState = MutableLiveData<String>().apply { value = "" }
    val transactionsSyncState = MutableLiveData<String>().apply { value = "" }
    val lastBlockHeight = MutableLiveData<String>().apply { value = "" }

    private var kit: SolanaKit? = null

    init {
        viewModelScope.launch {
            val kit = App.instance.awaitSolanaKit()
            this@BalanceViewModel.kit = kit

            launch {
                kit.balanceFlow.collect {
                    balance.postValue("Balance: $it")
                }
            }

            launch {
                kit.balanceSyncStateFlow.collect {
                    balanceSyncState.postValue("BalanceState: $it")
                }
            }

            launch {
                kit.tokenBalanceSyncStateFlow.collect {
                    tokenBalanceSyncState.postValue("TokenBalanceState: $it")
                }
            }

            launch {
                kit.transactionsSyncStateFlow.collect {
                    transactionsSyncState.postValue("TxSyncState: $it")
                }
            }

            launch {
                kit.lastBlockHeightFlow.collect {
                    lastBlockHeight.postValue("LastBlockHeight: $it")
                }
            }

            balance.postValue("Balance: ${kit.balance}")
            receiveAddress.postValue("Address: ${kit.receiveAddress}")
            balanceSyncState.postValue("SyncState: ${kit.syncState}")
            tokenBalanceSyncState.postValue("TokenSyncState: ${kit.tokenBalanceSyncState}")
            transactionsSyncState.postValue("TxSyncState: ${kit.transactionsSyncState}")
            lastBlockHeight.postValue("LastBlockHeight: ${kit.lastBlockHeight}")
        }
    }

    fun start() {
        kit?.start()
    }

    fun refresh() {
        kit?.refresh()
    }

    fun stop() {
        kit?.stop()
    }

}
