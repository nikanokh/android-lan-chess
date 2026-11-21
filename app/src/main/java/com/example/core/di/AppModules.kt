package com.example.core.di

import android.content.Context
import androidx.room.Room
import com.example.core.common.DefaultDispatcherProvider
import com.example.core.common.DispatcherProvider
import com.example.core.database.ChessDatabase
import com.example.core.database.dao.AppSettingsDao
import com.example.core.database.dao.GameDao
import com.example.core.database.dao.MoveDao
import com.example.core.database.dao.PlayerDao
import com.example.data.repository.GameRepositoryImpl
import com.example.data.repository.SettingsRepositoryImpl
import com.example.domain.repository.GameRepository
import com.example.domain.repository.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideChessDatabase(
        @ApplicationContext context: Context
    ): ChessDatabase = Room.databaseBuilder(
        context,
        ChessDatabase::class.java,
        ChessDatabase.DATABASE_NAME
    ).build()

    @Provides
    fun provideGameDao(database: ChessDatabase): GameDao = database.gameDao()

    @Provides
    fun provideMoveDao(database: ChessDatabase): MoveDao = database.moveDao()

    @Provides
    fun providePlayerDao(database: ChessDatabase): PlayerDao = database.playerDao()

    @Provides
    fun provideAppSettingsDao(database: ChessDatabase): AppSettingsDao = database.appSettingsDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class BindingsModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(
        impl: DefaultDispatcherProvider
    ): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindGameRepository(
        impl: GameRepositoryImpl
    ): GameRepository

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(
        impl: SettingsRepositoryImpl
    ): SettingsRepository

    @Binds
    @Singleton
    abstract fun bindNetworkConnectionManager(
        impl: com.example.core.network.GameSessionManager
    ): com.example.core.network.NetworkConnectionManager
}

@Module
@InstallIn(SingletonComponent::class)
object ChessEngineModule {

    @Provides
    fun provideChessEngine(): com.example.core.chess.ChessEngine =
        com.example.core.chess.ChesslibChessEngine()

    @Provides
    @Singleton
    fun provideMonotonicTimeProvider(): com.example.core.chess.MonotonicTimeProvider =
        com.example.core.chess.SystemMonotonicTimeProvider()
}

