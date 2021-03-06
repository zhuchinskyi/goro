package com.stanfy.enroscar.goro;

import android.os.Build;

import com.stanfy.enroscar.goro.Goro.GoroImpl;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link com.stanfy.enroscar.goro.Goro.GoroImpl}.
 */
@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, sdk = Build.VERSION_CODES.LOLLIPOP)
public class GoroImplTest {

  /** Goro instance. */
  private GoroImpl goro;

  /** Listener. */
  private GoroListener listener;

  /** Testing queues. */
  private TestingQueues testingQueues;

  @Before
  public void createGoro() {
    testingQueues = new TestingQueues();
    // this is not public API, yet the simplest way to get an instance
    // and it suits our test needs
    goro = new GoroImpl(testingQueues);
    listener = mock(GoroListener.class);
  }

  @Test(expected = GoroException.class)
  public void removeListenerShouldThrowOnUnknownListener() {
    goro.removeTaskListener(mock(GoroListener.class));
  }

  @Test
  public void shouldBeAbleToAddAndRemoveListeners() {
    GoroListener listener = mock(GoroListener.class);
    goro.addTaskListener(listener);
    goro.removeTaskListener(listener);
  }

  @Test
  public void scheduleShouldReturnFuture() {
    Callable<?> task = mock(Callable.class);
    Future future1 = goro.schedule("1", task);
    assertThat(future1).isNotNull();
    Future future2 = goro.schedule("2", task);
    assertThat(future2).isNotNull().isNotEqualTo(future1);
  }

  @Test
  public void shouldScheduleOnDefaultQueue() {
    goro = spy(goro);
    goro.schedule(mock(Callable.class));
    verify(goro).schedule(eq(Goro.DEFAULT_QUEUE), any(Callable.class));
  }

  @Test(expected = IllegalArgumentException.class)
  public void scheduleShouldThrowWhenTaskIsNull() {
    goro.schedule("1", null);
  }


  @Test
  public void shouldInvokeStartOnListeners() {
    goro.addTaskListener(listener);
    Callable<?> task = mock(Callable.class);
    goro.schedule(task);
    testingQueues.executeAll();
    verify(listener).onTaskStart(task);
  }

  @Test
  public void shouldInvokeFinishOnListeners() throws Exception {
    goro.addTaskListener(listener);
    Callable<?> task = mock(Callable.class);
    Object result = new Object();
    doReturn(result).when(task).call();

    goro.schedule(task);

    testingQueues.executeAll();

    InOrder order = inOrder(listener);
    order.verify(listener).onTaskStart(task);
    order.verify(listener).onTaskFinish(task, result);
  }

  @Test
  public void shouldInvokeCancelOnListeners() {
    goro.addTaskListener(listener);
    Callable<?> task = mock(Callable.class);
    goro.schedule(task).cancel(true);
    testingQueues.executeAll();

    verify(listener, never()).onTaskStart(task);
    verify(listener).onTaskCancel(task);  }

  @Test
  public void shouldInvokeErrorOnListeners() {
    goro.addTaskListener(listener);
    final Exception error = new Exception();
    Callable<?> task = new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        throw error;
      }
    };
    goro.schedule(task);
    testingQueues.executeAll();

    InOrder order = inOrder(listener);
    order.verify(listener).onTaskSchedule(task, Goro.DEFAULT_QUEUE);
    order.verify(listener).onTaskStart(task);
    order.verify(listener).onTaskError(task, error);
  }

  @Test
  public void shouldInvokeScheduleOnListeners() {
    goro.addTaskListener(listener);
    Callable task = mock(Callable.class);
    goro.schedule(task);
    verify(listener).onTaskSchedule(task, Goro.DEFAULT_QUEUE);
  }

  @Test
  public void getExecutorShouldReturnSerialExecutor() {
    Executor executor = goro.getExecutor(null);
    assertThat(testingQueues.getLastQueueName()).isEqualTo(Goro.DEFAULT_QUEUE);
    Runnable task = mock(Runnable.class);
    executor.execute(task);
    verify(task, never()).run();
    testingQueues.executeAll();
    verify(task).run();
  }

  @Test
  public void shouldPassNullQueueNameWhenAsked() {
    goro.schedule(null, mock(Callable.class));
    assertThat(testingQueues.getLastQueueName()).isNull();
  }

  @SuppressWarnings("unchecked")
  @Test
  public void immediateObserversShouldBeCalled() {
    FutureObserver observer = mock(FutureObserver.class);
    goro.schedule(mock(Callable.class)).subscribe(observer);
    verify(observer, never()).onSuccess(any());
    testingQueues.executeAll();
    verify(observer).onSuccess(any());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void observersShouldBeCalledOnTheirExecutors() {
    FutureObserver observer = mock(FutureObserver.class);
    final Runnable[] commands = new Runnable[1];
    //noinspection NullableProblems
    Executor executor = new Executor() {
      @Override
      public void execute(final Runnable command) {
        commands[0] = command;
      }
    };

    goro.schedule(mock(Callable.class)).subscribe(executor, observer);
    verify(observer, never()).onSuccess(any());
    testingQueues.executeAll();
    assertThat(commands[0]).isNotNull();
    commands[0].run();
    verify(observer).onSuccess(any());
  }

  @SuppressWarnings("unchecked")
  @Test
  public void errorsShouldBePassedToObservers() throws Exception {
    FutureObserver observer = mock(FutureObserver.class);
    Callable<?> task = mock(Callable.class);
    Exception e = new Exception();
    doThrow(e).when(task).call();
    goro.schedule(task).subscribe(observer);

    testingQueues.executeAll();
    verify(observer).onError(e);
  }

  @Test
  public void clearShouldRemoveTasks() {
    final AtomicInteger counter = new AtomicInteger(0);
    goro.schedule("q1", new Callable<Object>() {
      @Override
      public Object call() throws Exception {
        return counter.incrementAndGet();
      }
    });
    goro.getExecutor("q1").execute(new Runnable() {
      @Override
      public void run() {
        counter.incrementAndGet();
      }
    });
    goro.clear("q1");
    testingQueues.executeAll();
    assertThat(counter.get()).describedAs("some task has been executed").isZero();
    assertThat(testingQueues.getLastQueueName()).isEqualTo("q1");
  }

}
