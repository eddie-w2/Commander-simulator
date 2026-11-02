package com.eddiew.commandersim.sim.forge;

// One Forge worker process. Arguments and replies are Java-serialized objects (see ForgeWorkerService).
interface IForgeWorker {
    byte[] prepare(String packPath, String resPath, String workPath, in byte[] decks);
    byte[] play(in String[] seatDeckIds, long seed, int maxRounds, long maxMillis);
    String status();
}
