/*
 * Copyright (c) 2017-2018 Mithril coin.
 *
 * The MIT License
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package io.mithrilcoin.eoscommander.data;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.mithrilcoin.eoscommander.crypto.ec.EosPrivateKey;
import io.mithrilcoin.eoscommander.data.local.repository.EosAccountRepository;
import io.mithrilcoin.eoscommander.data.prefs.PreferencesHelper;
import io.mithrilcoin.eoscommander.data.remote.NodeosApi;
import io.mithrilcoin.eoscommander.data.remote.model.abi.EosAbiMain;
import io.mithrilcoin.eoscommander.data.remote.model.api.AccountInfoRequest;
import io.mithrilcoin.eoscommander.data.remote.model.api.Action;
import io.mithrilcoin.eoscommander.data.remote.model.api.EosChainInfo;
import io.mithrilcoin.eoscommander.data.remote.model.api.GetTableRequest;
import io.mithrilcoin.eoscommander.data.remote.model.api.JsonToBinRequest;
import io.mithrilcoin.eoscommander.data.remote.model.api.PushTxnResponse;
import io.mithrilcoin.eoscommander.data.remote.model.chain.GetCodeRequest;
import io.mithrilcoin.eoscommander.data.remote.model.chain.GetRequiredKeys;
import io.mithrilcoin.eoscommander.data.remote.model.chain.PackedTransaction;
import io.mithrilcoin.eoscommander.data.remote.model.chain.SignedTransaction;
import io.mithrilcoin.eoscommander.data.remote.model.types.EosNewAccount;
import io.mithrilcoin.eoscommander.data.remote.model.types.EosTransfer;
import io.mithrilcoin.eoscommander.data.remote.model.types.TypeChainId;
import io.mithrilcoin.eoscommander.data.util.EstimateRsc;
import io.mithrilcoin.eoscommander.data.wallet.EosWalletManager;
import io.mithrilcoin.eoscommander.util.Consts;
import io.mithrilcoin.eoscommander.util.Utils;
import io.reactivex.Observable;

import static io.mithrilcoin.eoscommander.util.Consts.EOS_SYSTEM_ACCOUNT;
import static io.mithrilcoin.eoscommander.util.Consts.TX_EXPIRATION_IN_MILSEC;

/**
 * Created by swapnibble on 2017-11-03.
 */
@Singleton
public class EoscDataManager {

    private final NodeosApi mNodeosApi;
    private final PreferencesHelper mPrefHelper;
    private final EosWalletManager  mWalletMgr;
    private final EosAccountRepository mAccountRepository;

    private HashMap<String,EosAbiMain> mAbiObjHouse;

    @Inject
    public EoscDataManager(NodeosApi nodeosApi, EosWalletManager walletManager, EosAccountRepository accountRepository, PreferencesHelper prefHelper) {
        mNodeosApi = nodeosApi;
        mWalletMgr  = walletManager;
        mAccountRepository = accountRepository;
        mPrefHelper = prefHelper;

        mWalletMgr.setDir( mPrefHelper.getWalletDirFile() );
        mWalletMgr.openExistingsInDir();

        mAbiObjHouse = new HashMap<>();
    }

    public EosWalletManager getWalletManager() { return mWalletMgr; }

    public PreferencesHelper getPreferenceHelper() { return mPrefHelper; }


    public void addAccountHistory(String... accountNames){
        mAccountRepository.addAll(accountNames);
    }

    public void addAccountHistory(List<String> accountNames){
        mAccountRepository.addAll(accountNames);
    }

    public void deleteAccountHistory( String accountName ) {
        mAccountRepository.delete( accountName );
    }

    public List<String> searchAccount( String nameStarts){
        return mAccountRepository.searchName( nameStarts );
    }

    public void pushAbiObject(String key, EosAbiMain abi){
        mAbiObjHouse.put(key , abi );
    }

    public EosAbiMain popAbiObject( String key) {
        return mAbiObjHouse.remove( key );
    }

    public void clearAbiObjects(){
        mAbiObjHouse.clear();
    }

    public Observable<EosChainInfo> getChainInfo(){

        return mNodeosApi.readInfo("get_info");
    }

    public Observable<String> getTable( String accountName, String code, String table ){
        return mNodeosApi.getTable( new GetTableRequest(accountName, code, table))
                .map( tableResult -> Utils.prettyPrintJson(tableResult));
    }

    public Observable<EosPrivateKey[]> createKey( int count ) {
        return Observable.fromCallable( () -> {
            EosPrivateKey[] retKeys = new EosPrivateKey[ count ];
            for ( int i = 0; i < count; i++) {
                retKeys[i] = new EosPrivateKey();
            }

            return retKeys;
        } );
    }

    private SignedTransaction createTransaction( String contract, String actionName, String dataAsHex,
                                String[] permissions, EosChainInfo chainInfo ){
        Action action = new Action(contract, actionName);
        action.setAuthorization(permissions);
        action.setData( dataAsHex );

        SignedTransaction txn = new SignedTransaction();
        txn.addAction( action );
        txn.putSignatures( new ArrayList<>());


        if ( null != chainInfo ) {
            txn.setReferenceBlock(chainInfo.getHeadBlockId());
            txn.setExpiration(chainInfo.getTimeAfterHeadBlockTime(TX_EXPIRATION_IN_MILSEC));
        }

        return txn;
    }

    private SignedTransaction estimateResources( SignedTransaction txn, int keyCount ) {
        return new EstimateRsc().estimate( txn, PackedTransaction.CompressType.none, keyCount);
    }


    private Observable<PackedTransaction> signAndPackTransaction(SignedTransaction txnBeforeSign ) {
        if ( mPrefHelper.shouldSkipSigning() ) {
            // TODO estimateRscUsages
            return Observable.just( new PackedTransaction( estimateResources(txnBeforeSign , 0) ) );
        }

        return mNodeosApi.getRequiredKeys( new GetRequiredKeys( txnBeforeSign, mWalletMgr.listPubKeys() ))
                .map( keys -> mWalletMgr.signTransaction( estimateResources(txnBeforeSign, keys.getKeys().size()), keys.getKeys(), new TypeChainId() ))
                .map( signedTx -> new PackedTransaction(signedTx) );
    }


    private String[] getActivePermission(String accountName ) {
        return new String[] { accountName + "@active" };
    }


    public Observable<JsonObject> readAccountInfo(String accountName ) {
        return mNodeosApi.getAccountInfo(new AccountInfoRequest(accountName));
    }

    public Observable<JsonObject> transferEos( String from, String to, long amount, String memo ) {

        EosTransfer transfer = new EosTransfer(from, to, amount, memo);

        return pushAction( EOS_SYSTEM_ACCOUNT, transfer.getActionName(), Utils.prettyPrintJson( transfer), getActivePermission(from));
    }

    public Observable<PushTxnResponse> createAccount(EosNewAccount newAccountData) {

        return getChainInfo()
                .map( info -> createTransaction( Consts.EOS_SYSTEM_ACCOUNT, newAccountData.getTypeName(), newAccountData.getAsHex()
                                    , getActivePermission( newAccountData.getCreatorName() ), info ))
                .flatMap( txn -> signAndPackTransaction( txn))
                .flatMap( packedTxn -> mNodeosApi.pushTransaction( packedTxn ));
    }

    public Observable<JsonObject> getTransactions(String accountName ) {

        JsonObject gsonObject = new JsonObject();
        gsonObject.addProperty( NodeosApi.GET_TRANSACTIONS_KEY, accountName);

        return mNodeosApi.getAccountHistory( NodeosApi.ACCOUNT_HISTORY_GET_TRANSACTIONS, gsonObject);
    }

    public Observable<JsonObject> getServants( String accountName ) {
        // controlling_account

        JsonObject gsonObject = new JsonObject();
        gsonObject.addProperty( NodeosApi.GET_SERVANTS_KEY, accountName);

        return mNodeosApi.getAccountHistory( NodeosApi.ACCOUNT_HISTORY_GET_SERVANTS, gsonObject);
    }

    public Observable<JsonObject> pushAction(String contract, String action, String data, String[] permissions) {

        return mNodeosApi.jsonToBin( new JsonToBinRequest( contract, action, data ))
                .flatMap( jsonToBinResp -> getChainInfo()
                                            .map( info -> createTransaction( contract, action, jsonToBinResp.getBinargs(), permissions, info )) )
                .flatMap( this::signAndPackTransaction )
                .flatMap( mNodeosApi::pushTransactionRetJson );
    }

    public Observable<EosAbiMain> getCodeAbi( String contract ) {
        return mNodeosApi.getCode( new GetCodeRequest(contract))
                .filter( codeResp -> codeResp.isValidCode())
                .map( result -> new GsonBuilder().excludeFieldsWithoutExposeAnnotation()
                        .create().fromJson(result.getAbi(), EosAbiMain.class) );
    }

    public Observable<EosAbiMain> getAbiMainFromJson( String jsonStr ) {
        return Observable.just( new GsonBuilder().excludeFieldsWithoutExposeAnnotation()
                .create().fromJson(jsonStr, EosAbiMain.class));
    }
}
